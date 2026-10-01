package com.fakejira.auth.oauth;

import com.fakejira.mail.MailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * SAML 2.0 single sign-on (service provider side): an unsigned AuthnRequest over the HTTP-Redirect binding, and the
 * identity provider's response over HTTP-POST. The response must be signed (the whole response or the assertion)
 * with the configured IdP key; signature, issuer, audience, recipient, validity window and InResponseTo are all
 * checked, and each assertion is accepted once.
 *
 * <p>The IdP posts to {@code /api/auth/oauth/saml/acs}. That cross-site POST carries no cookies, so after checking the
 * response we redirect to the ordinary callback with a one-time ticket; the callback then checks the browser cookie,
 * exactly like Google/GitHub sign-in.
 */
@Service
public class SamlService {

    static final String PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    static final String ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";
    static final String DSIG = XMLSignature.XMLNS;
    static final String SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    static final Duration SKEW = Duration.ofMinutes(3);
    static final Duration TICKET_VALIDITY = Duration.ofMinutes(2);
    static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static final List<String> EMAIL_ATTRIBUTES = List.of("email", "mail", "emailaddress", "Email",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress", "urn:oid:0.9.2342.19200300.100.1.3");
    private static final List<String> NAME_ATTRIBUTES = List.of("displayName", "name", "cn",
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name", "http://schemas.microsoft.com/identity/claims/displayname",
            "urn:oid:2.16.840.1.113730.3.1.241");
    private static final Set<String> ALLOWED_TRANSFORMS = Set.of(Transform.ENVELOPED, CanonicalizationMethod.EXCLUSIVE,
            CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS);

    /** A checked response, waiting for the browser to arrive at the callback. */
    record Ticket(String state, OAuthService.Profile profile, Instant expires) {
    }

    private final String ssoUrl;
    private final String idpEntityId;
    private final PublicKey idpKey;
    private final String label;
    private final boolean trustEmail;
    private final MailService site;
    private final OAuthService oauth;
    private final SecureRandom random = new SecureRandom();
    private final com.fakejira.cluster.Cluster cluster;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public SamlService(@Value("${app.saml.idp-sso-url:}") String ssoUrl,
                       @Value("${app.saml.idp-entity-id:}") String idpEntityId,
                       @Value("${app.saml.idp-certificate:}") String idpCertificate,
                       @Value("${app.saml.label:Company sign-in}") String label,
                       @Value("${app.saml.trust-email:true}") boolean trustEmail,
                       MailService site, OAuthService oauth, com.fakejira.cluster.Cluster cluster,
                       com.fasterxml.jackson.databind.ObjectMapper json) {
        this.cluster = cluster;
        this.json = json;
        this.ssoUrl = ssoUrl.trim();
        this.idpEntityId = idpEntityId.trim();
        this.idpKey = idpCertificate.isBlank() ? null : parseKey(idpCertificate);
        this.label = label;
        this.trustEmail = trustEmail;
        this.site = site;
        this.oauth = oauth;
    }

    public boolean enabled() {
        return !ssoUrl.isEmpty() && idpKey != null;
    }

    public String label() {
        return label;
    }

    public String entityId() {
        return site.link("/api/auth/saml/metadata");
    }

    public String acsUrl() {
        return site.link("/api/auth/oauth/saml/acs");
    }

    /** Service provider metadata for the IdP administrator. */
    public String metadata() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:SPSSODescriptor AuthnRequestsSigned="false" WantAssertionsSigned="true" protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>
                    <md:NameIDFormat>urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress</md:NameIDFormat>
                    <md:AssertionConsumerService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST" Location="%s" index="0" isDefault="true"/>
                  </md:SPSSODescriptor>
                </md:EntityDescriptor>
                """.formatted(xml(entityId()), xml(acsUrl()));
    }

    /** The IdP URL with a deflated AuthnRequest and our signed state as RelayState. */
    String authnRequestUrl(String requestId, String state) {
        String request = "<samlp:AuthnRequest xmlns:samlp=\"" + PROTOCOL + "\" xmlns:saml=\"" + ASSERTION + "\" ID=\""
                + requestId + "\" Version=\"2.0\" IssueInstant=\"" + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                + "\" Destination=\"" + xml(ssoUrl) + "\" AssertionConsumerServiceURL=\"" + xml(acsUrl())
                + "\" ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"><saml:Issuer>" + xml(entityId())
                + "</saml:Issuer><samlp:NameIDPolicy AllowCreate=\"true\"/></samlp:AuthnRequest>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(out, new Deflater(Deflater.DEFLATED, true))) {
            deflate.write(request.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return ssoUrl + (ssoUrl.contains("?") ? "&" : "?") + "SAMLRequest="
                + URLEncoder.encode(Base64.getEncoder().encodeToString(out.toByteArray()), StandardCharsets.UTF_8)
                + "&RelayState=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    /**
     * Checks the IdP's POST and returns the path to continue at: the callback with a one-time ticket, or an error.
     */
    public String acs(String samlResponse, String relayState) {
        Jwt state = oauth.readState(relayState);
        if (state == null || !"saml".equals(state.getClaimAsString("provider"))) {
            return "/api/auth/oauth/saml/callback?error=state";
        }
        OAuthService.Profile profile;
        try {
            profile = verify(samlResponse, state.getClaimAsString("request"), Instant.now());
        } catch (SamlException e) {
            org.slf4j.LoggerFactory.getLogger(SamlService.class).warn("Rejected a SAML response: {}", e.getMessage());
            return "/api/auth/oauth/saml/callback?error=invalid";
        }
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            // Shared, so the browser can come back to any instance.
            cluster.put("saml:ticket:" + ticket, json.writeValueAsString(new Ticket(relayState, profile, Instant.now().plus(TICKET_VALIDITY))),
                    TICKET_VALIDITY);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return "/api/auth/oauth/saml/callback?code=" + ticket + "&state=" + URLEncoder.encode(relayState, StandardCharsets.UTF_8);
    }

    /** The profile for a ticket; each ticket works once, only with the state it was issued for. */
    OAuthService.Profile redeem(String ticket, String state) throws IOException {
        String stored = ticket == null ? null : cluster.take("saml:ticket:" + ticket);
        Ticket found = stored == null ? null : json.readValue(stored, Ticket.class);
        if (found == null || found.expires().isBefore(Instant.now()) || !found.state().equals(state)) {
            throw new IOException("Unknown or expired SAML ticket");
        }
        return found.profile();
    }

    static class SamlException extends Exception {
        SamlException(String message) {
            super(message);
        }
    }

    /** Validates a base64 SAML response for {@code requestId}; returns the signed-in person. */
    OAuthService.Profile verify(String encoded, String requestId, Instant now) throws SamlException {
        if (encoded == null || encoded.length() > MAX_RESPONSE_BYTES * 4 / 3 + 4) {
            throw new SamlException("missing or oversized response");
        }
        byte[] raw;
        try {
            raw = Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new SamlException("not base64");
        }
        Document doc = parse(raw);
        Element response = doc.getDocumentElement();
        if (!is(response, PROTOCOL, "Response")) {
            throw new SamlException("not a SAML Response");
        }
        Element statusCode = first(first(response, PROTOCOL, "Status"), PROTOCOL, "StatusCode");
        if (statusCode == null || !SUCCESS.equals(statusCode.getAttribute("Value"))) {
            throw new SamlException("the IdP reported a failure");
        }
        if (response.hasAttribute("Destination") && !acsUrl().equals(response.getAttribute("Destination"))) {
            throw new SamlException("wrong Destination");
        }
        if (response.hasAttribute("InResponseTo") && !response.getAttribute("InResponseTo").equals(requestId)) {
            throw new SamlException("InResponseTo does not match");
        }
        if (!children(response, ASSERTION, "EncryptedAssertion").isEmpty()) {
            throw new SamlException("encrypted assertions are not supported");
        }
        List<Element> assertions = children(response, ASSERTION, "Assertion");
        if (assertions.size() != 1) {
            throw new SamlException("expected exactly one assertion");
        }
        Element assertion = assertions.get(0);
        // IDs must be unique, or a reference could resolve to a different element than the one we read.
        if (!markIds(doc.getDocumentElement(), new java.util.HashSet<>())) {
            throw new SamlException("duplicate IDs");
        }

        // A valid signature over the response or over the assertion itself.
        boolean responseSigned = verifySignature(response);
        boolean assertionSigned = verifySignature(assertion);
        if (!responseSigned && !assertionSigned) {
            throw new SamlException("the response is not signed");
        }

        Element issuer = first(assertion, ASSERTION, "Issuer");
        if (!idpEntityId.isEmpty() && (issuer == null || !idpEntityId.equals(issuer.getTextContent().trim()))) {
            throw new SamlException("unexpected issuer");
        }
        Element conditions = first(assertion, ASSERTION, "Conditions");
        if (conditions == null) {
            throw new SamlException("no conditions");
        }
        checkWindow(conditions.getAttribute("NotBefore"), conditions.getAttribute("NotOnOrAfter"), now);
        boolean audienceOk = false;
        for (Element restriction : children(conditions, ASSERTION, "AudienceRestriction")) {
            for (Element audience : children(restriction, ASSERTION, "Audience")) {
                audienceOk |= entityId().equals(audience.getTextContent().trim());
            }
        }
        if (!audienceOk) {
            throw new SamlException("this service is not the audience");
        }

        Element subject = first(assertion, ASSERTION, "Subject");
        Element nameId = first(subject, ASSERTION, "NameID");
        if (nameId == null || nameId.getTextContent().isBlank()) {
            throw new SamlException("no NameID");
        }
        boolean confirmed = false;
        for (Element confirmation : children(subject, ASSERTION, "SubjectConfirmation")) {
            Element data = first(confirmation, ASSERTION, "SubjectConfirmationData");
            if (!"urn:oasis:names:tc:SAML:2.0:cm:bearer".equals(confirmation.getAttribute("Method")) || data == null) {
                continue;
            }
            if (acsUrl().equals(data.getAttribute("Recipient")) && requestId.equals(data.getAttribute("InResponseTo"))
                    && !data.getAttribute("NotOnOrAfter").isEmpty()) {
                checkWindow(data.getAttribute("NotBefore"), data.getAttribute("NotOnOrAfter"), now);
                confirmed = true;
            }
        }
        if (!confirmed) {
            throw new SamlException("no valid bearer subject confirmation for this request");
        }

        String assertionId = assertion.getAttribute("ID");
        Instant keepUntil = parseTime(conditions.getAttribute("NotOnOrAfter"), now.plus(Duration.ofHours(1))).plus(SKEW);
        Duration keep = Duration.between(now, keepUntil);
        if (!cluster.putIfAbsent("saml:assertion:" + assertionId, "used", keep.isNegative() ? SKEW : keep.plus(SKEW))) {
            throw new SamlException("assertion already used");
        }

        String subjectId = nameId.getTextContent().trim();
        String email = attribute(assertion, EMAIL_ATTRIBUTES);
        if (email == null && nameId.getAttribute("Format").endsWith(":emailAddress")) {
            email = subjectId;
        }
        String name = attribute(assertion, NAME_ATTRIBUTES);
        String login = email != null ? email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@')) : null;
        return new OAuthService.Profile(subjectId, email, email != null && trustEmail, login, name);
    }

    /** Validates the ds:Signature that is a direct child of {@code element}, if there is one. */
    private boolean verifySignature(Element element) throws SamlException {
        List<Element> signatures = children(element, DSIG, "Signature");
        if (signatures.isEmpty()) {
            return false;
        }
        if (signatures.size() > 1) {
            throw new SamlException("more than one signature");
        }
        try {
            DOMValidateContext context = new DOMValidateContext(KeySelector.singletonKeySelector(idpKey), signatures.get(0));
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
            // The signature must cover exactly this element (no wrapping), with only the expected transforms.
            List<?> references = signature.getSignedInfo().getReferences();
            if (references.size() != 1) {
                throw new SamlException("expected one signed reference");
            }
            Reference reference = (Reference) references.get(0);
            if (!("#" + element.getAttribute("ID")).equals(reference.getURI()) || element.getAttribute("ID").isEmpty()) {
                throw new SamlException("the signature covers a different element");
            }
            for (Object transform : reference.getTransforms()) {
                if (!ALLOWED_TRANSFORMS.contains(((Transform) transform).getAlgorithm())) {
                    throw new SamlException("unexpected transform");
                }
            }
            if (!signature.validate(context)) {
                throw new SamlException("bad signature");
            }
            return true;
        } catch (MarshalException | XMLSignatureException | ClassCastException e) {
            throw new SamlException("unreadable signature: " + e.getMessage());
        }
    }

    /** Registers every ID attribute for signature references; false when an ID repeats. */
    private static boolean markIds(Element element, Set<String> seen) {
        if (element.hasAttribute("ID")) {
            if (!seen.add(element.getAttribute("ID"))) {
                return false;
            }
            element.setIdAttribute("ID", true);
        }
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element e && !markIds(e, seen)) {
                return false;
            }
        }
        return true;
    }

    private static void checkWindow(String notBefore, String notOnOrAfter, Instant now) throws SamlException {
        if (!notBefore.isEmpty() && now.plus(SKEW).isBefore(parseTime(notBefore, null))) {
            throw new SamlException("not valid yet");
        }
        if (!notOnOrAfter.isEmpty() && !now.minus(SKEW).isBefore(parseTime(notOnOrAfter, null))) {
            throw new SamlException("expired");
        }
    }

    private static Instant parseTime(String value, Instant fallback) throws SamlException {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new SamlException("bad timestamp");
        }
    }

    private static String attribute(Element assertion, List<String> names) {
        for (Element statement : children(assertion, ASSERTION, "AttributeStatement")) {
            for (Element attribute : children(statement, ASSERTION, "Attribute")) {
                if (names.contains(attribute.getAttribute("Name")) || names.contains(attribute.getAttribute("FriendlyName"))) {
                    Element value = first(attribute, ASSERTION, "AttributeValue");
                    if (value != null && !value.getTextContent().isBlank()) {
                        return value.getTextContent().trim();
                    }
                }
            }
        }
        return null;
    }

    private static Document parse(byte[] raw) throws SamlException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new ByteArrayInputStream(raw));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new SamlException("not well-formed XML");
        }
    }

    private static boolean is(Element element, String ns, String name) {
        return element != null && ns.equals(element.getNamespaceURI()) && name.equals(element.getLocalName());
    }

    private static List<Element> children(Element parent, String ns, String name) {
        List<Element> found = new ArrayList<>();
        if (parent == null) {
            return found;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element e && is(e, ns, name)) {
                found.add(e);
            }
        }
        return found;
    }

    private static Element first(Element parent, String ns, String name) {
        List<Element> found = children(parent, ns, name);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Accepts a PEM or bare base64 X.509 certificate, or a PEM public key. */
    static PublicKey parseKey(String text) {
        String body = text.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        try {
            if (text.contains("PUBLIC KEY")) {
                return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            }
            return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("app.saml.idp-certificate is not a valid certificate or public key", e);
        }
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
