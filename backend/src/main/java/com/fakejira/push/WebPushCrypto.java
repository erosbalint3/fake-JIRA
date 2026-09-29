package com.fakejira.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Web Push message encryption (RFC 8291, "aes128gcm") and VAPID (RFC 8292) signing,
 * implemented with the JDK only.
 */
public final class WebPushCrypto {

    static final int RECORD_SIZE = 4096;
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Encrypts {@code payload} for a subscription's public key and auth secret. */
    public static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair serverKeys, byte[] salt)
            throws GeneralSecurityException {
        byte[] asPublic = rawPublicKey((ECPublicKey) serverKeys.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(serverKeys.getPrivate());
        agreement.doPhase(publicKey(uaPublic), true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[]{1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0, 1})), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        // Single record: payload followed by the 0x02 "last record" delimiter, no padding.
        byte[] ciphertext = cipher.doFinal(concat(payload, new byte[]{2}));

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), ciphertext);
    }

    /** VAPID "Authorization" header value for a push service origin. */
    public static String vapidAuthorization(String audience, String subject, KeyPair vapidKeys, long expiresEpochSeconds)
            throws GeneralSecurityException {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresEpochSeconds
                + ",\"sub\":\"" + subject.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(vapidKeys.getPrivate());
        signer.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        String signature = b64.encodeToString(derToJose(signer.sign()));
        String publicKey = b64.encodeToString(rawPublicKey((ECPublicKey) vapidKeys.getPublic()));
        return "vapid t=" + header + "." + claims + "." + signature + ", k=" + publicKey;
    }

    // ------------------------------------------------------------ key encoding helpers

    /** Uncompressed point: 0x04 || X (32 bytes) || Y (32 bytes). */
    public static byte[] rawPublicKey(ECPublicKey key) {
        return concat(new byte[]{4}, fixed(key.getW().getAffineX()), fixed(key.getW().getAffineY()));
    }

    public static byte[] rawPrivateKey(ECPrivateKey key) {
        return fixed(key.getS());
    }

    public static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Expected an uncompressed P-256 public key");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
    }

    public static PrivateKey privateKey(byte[] raw) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
    }

    public static KeyPair keyPair(byte[] rawPublic, byte[] rawPrivate) throws GeneralSecurityException {
        return new KeyPair(publicKey(rawPublic), privateKey(rawPrivate));
    }

    /** Converts a DER ECDSA signature to the 64-byte r||s form JWTs use. */
    static byte[] derToJose(byte[] der) {
        int offset = der[1] < 0 ? 3 : 2;
        int rLength = der[offset + 1];
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        int sStart = offset + 2 + rLength;
        int sLength = der[sStart + 1];
        byte[] s = Arrays.copyOfRange(der, sStart + 2, sStart + 2 + sLength);
        return concat(fixed(new BigInteger(1, r)), fixed(new BigInteger(1, s)));
    }

    private static byte[] fixed(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == 32) {
            return bytes;
        }
        byte[] out = new byte[32];
        int copy = Math.min(32, bytes.length);
        System.arraycopy(bytes, bytes.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static ECParameterSpec p256() {
        return ((ECPublicKey) generateKeyPair().getPublic()).getParams();
    }
}
