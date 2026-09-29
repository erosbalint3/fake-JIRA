package com.fakejira.push;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WebPushCryptoTest {

    private static byte[] b64(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /** RFC 8291, Appendix A: the exact example message. */
    @Test
    void matchesRfc8291TestVector() throws Exception {
        KeyPair server = WebPushCrypto.keyPair(
                b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"),
                b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        byte[] body = WebPushCrypto.encrypt(
                "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8),
                b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"),
                b64("BTBZMqHH6r4Tts7J_aSIgg"),
                server,
                b64("DGv6ra1nlYgDCS1FRnbzlw"));
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(body)).isEqualTo(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    /** Independent check: decrypt as a browser would, with freshly generated keys. */
    @Test
    void roundTripsWithGeneratedKeys() throws Exception {
        KeyPair browser = WebPushCrypto.generateKeyPair();
        KeyPair server = WebPushCrypto.generateKeyPair();
        byte[] auth = new byte[16];
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        new java.security.SecureRandom().nextBytes(salt);
        byte[] uaPublic = WebPushCrypto.rawPublicKey((ECPublicKey) browser.getPublic());
        byte[] body = WebPushCrypto.encrypt("{\"title\":\"hi\"}".getBytes(StandardCharsets.UTF_8), uaPublic, auth, server, salt);

        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + 65);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(browser.getPrivate());
        agreement.doPhase(WebPushCrypto.publicKey(asPublic), true);
        byte[] prkKey = hmac(auth, agreement.generateSecret());
        byte[] ikm = hmac(prkKey, WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII),
                new byte[]{0}, uaPublic, asPublic, new byte[]{1}));
        byte[] prk = hmac(Arrays.copyOf(body, 16), ikm);
        byte[] cek = Arrays.copyOf(hmac(prk, WebPushCrypto.concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 16);
        byte[] nonce = Arrays.copyOf(hmac(prk, WebPushCrypto.concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(Arrays.copyOfRange(body, 86, body.length));
        assertThat(new String(plain, 0, plain.length - 1, StandardCharsets.UTF_8)).isEqualTo("{\"title\":\"hi\"}");
        assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
    }

    @Test
    void vapidTokenIsAVerifiableEs256Jwt() throws Exception {
        KeyPair vapid = WebPushCrypto.generateKeyPair();
        String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com", "mailto:admin@example.com", vapid, 2_000_000_000L);
        String token = header.substring("vapid t=".length(), header.indexOf(','));
        String[] parts = token.split("\\.");
        assertThat(new String(b64(parts[1]), StandardCharsets.UTF_8)).contains("\"aud\":\"https://fcm.googleapis.com\"");
        byte[] raw = b64(parts[2]);
        assertThat(raw).hasSize(64);
        // Re-encode r||s as DER and verify with the JDK.
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(vapid.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(raw)).isTrue();
        assertThat(header).endsWith(", k=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(WebPushCrypto.rawPublicKey((ECPublicKey) vapid.getPublic())));
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
