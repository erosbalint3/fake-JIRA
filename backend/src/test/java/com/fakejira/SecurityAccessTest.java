package com.fakejira;

import com.fakejira.admin.SecurityPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Company sign-in (OpenID Connect, SAML, LDAP), SCIM provisioning, the organization's access policy (require SSO,
 * session length, IP allowlist), suspended accounts and sign-in alerts.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAccessTest extends ApiTestSupport {

    static final String IDP = "https://idp.example.com";
    static final KeyPair IDP_KEYS;
    static final KeyPair ATTACKER_KEYS;
    static final HttpServer OIDC;
    static final InMemoryDirectoryServer LDAP;
    static final ObjectMapper JSON = new ObjectMapper();

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            IDP_KEYS = generator.generateKeyPair();
            ATTACKER_KEYS = generator.generateKeyPair();

            OIDC = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OIDC.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String base = "http://127.0.0.1:" + OIDC.getAddress().getPort();
                Object body = switch (path) {
                    case "/.well-known/openid-configuration" -> Map.of("issuer", base, "authorization_endpoint", base + "/authorize",
                            "token_endpoint", base + "/token", "userinfo_endpoint", base + "/userinfo");
                    case "/token" -> Map.of("access_token", "oidc-access", "token_type", "Bearer");
                    case "/userinfo" -> "Bearer oidc-access".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                            ? Map.of("sub", "okta-42", "email", "erin@corp.example", "email_verified", true, "name", "Erin Okta",
                            "preferred_username", "erin")
                            : Map.of("error", "invalid_token");
                    default -> Map.of("error", "not found");
                };
                byte[] bytes = JSON.writeValueAsBytes(body);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(path.equals("/userinfo") && ((Map<?, ?>) body).containsKey("error") ? 401 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            OIDC.start();

            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
            config.addAdditionalBindCredentials("cn=reader", "reader-secret");
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("default", 0));
            LDAP = new InMemoryDirectoryServer(config);
            LDAP.add("dn: dc=example,dc=com", "objectClass: top", "objectClass: domain", "dc: example");
            LDAP.add("dn: ou=people,dc=example,dc=com", "objectClass: top", "objectClass: organizationalUnit", "ou: people");
            LDAP.add("dn: uid=dave,ou=people,dc=example,dc=com", "objectClass: top", "objectClass: inetOrgPerson",
                    "uid: dave", "cn: Dave Directory", "sn: Directory", "mail: dave@corp.example", "userPassword: dave-pass");
            LDAP.add("dn: uid=nomail,ou=people,dc=example,dc=com", "objectClass: top", "objectClass: inetOrgPerson",
                    "uid: nomail", "cn: No Mail", "sn: Mail", "userPassword: nomail-pass");
            LDAP.startListening();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("app.oauth.oidc.issuer", () -> "http://127.0.0.1:" + OIDC.getAddress().getPort());
        registry.add("app.oauth.oidc.client-id", () -> "fj-client");
        registry.add("app.oauth.oidc.client-secret", () -> "fj-secret");
        registry.add("app.oauth.oidc.label", () -> "Okta");
        registry.add("app.saml.idp-sso-url", () -> IDP + "/sso");
        registry.add("app.saml.idp-entity-id", () -> IDP);
        registry.add("app.saml.idp-certificate", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(IDP_KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
        registry.add("app.ldap.url", () -> "ldap://127.0.0.1:" + LDAP.getListenPort());
        registry.add("app.ldap.bind-dn", () -> "cn=reader");
        registry.add("app.ldap.bind-password", () -> "reader-secret");
        registry.add("app.ldap.user-search-base", () -> "ou=people,dc=example,dc=com");
    }

    @Autowired
    SecurityPolicy policy;

    @AfterEach
    void resetPolicy() {
        policy.save(new SecurityPolicy.Policy(false, 1, 0, List.of(), true), "127.0.0.1");
    }

    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    @Autowired
    com.fakejira.user.UserRepository users;

    private Account theAdmin() throws Exception {
        Account account = register();
        tx.executeWithoutResult(st -> users.findById(account.id()).orElseThrow().setAdmin(true));
        return account;
    }

    /** Starts a sign-in; returns {url, nonce cookie}. */
    private String[] start(String provider) throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/auth/oauth/" + provider + "/url")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andReturn().getResponse();
        return new String[]{json.readTree(response.getContentAsString()).get("url").asText(), response.getCookie("fj_oauth").getValue()};
    }

    private static Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        String q = URI.create(url).getRawQuery();
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            params.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String fragment(MockHttpServletResponse response) {
        String location = response.getHeader("Location");
        assertThat(location).startsWith("/oauth-complete#");
        return URLDecoder.decode(location.substring("/oauth-complete#".length()), StandardCharsets.UTF_8);
    }

    private JsonNode me(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // ---- OpenID Connect -------------------------------------------------------------------------------------------

    @Test
    void openIdConnectSignInUsesDiscovery() throws Exception {
        theAdmin();
        JsonNode providers = json.readTree(mvc.perform(get("/api/auth/providers")).andReturn().getResponse().getContentAsString());
        assertThat(providers.toString()).contains("\"oidc\"").contains("Okta").contains("\"saml\"");

        String[] started = start("oidc");
        Map<String, String> params = query(started[0]);
        assertThat(started[0]).startsWith("http://127.0.0.1:" + OIDC.getAddress().getPort() + "/authorize?");
        assertThat(params.get("scope")).isEqualTo("openid email profile");
        MockHttpServletResponse done = mvc.perform(get("/api/auth/oauth/oidc/callback").param("code", "abc")
                .param("state", params.get("state")).cookie(new Cookie("fj_oauth", started[1]))).andReturn().getResponse();
        String outcome = fragment(done);
        assertThat(outcome).startsWith("token=");
        JsonNode user = me(outcome.substring(6));
        assertThat(user.toString()).contains("erin@corp.example").contains("Erin Okta");

        // Without the browser cookie the callback is refused.
        String[] again = start("oidc");
        String refused = fragment(mvc.perform(get("/api/auth/oauth/oidc/callback").param("code", "abc")
                .param("state", query(again[0]).get("state"))).andReturn().getResponse());
        assertThat(refused).startsWith("error=");
    }

    // ---- SAML -----------------------------------------------------------------------------------------------------

    record SamlSetup(String requestId, String relayState, String nonce, String acs, String entityId) {
    }

    private SamlSetup startSaml() throws Exception {
        String metadata = mvc.perform(get("/api/auth/saml/metadata")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String entityId = metadata.replaceAll("(?s).*entityID=\"([^\"]+)\".*", "$1");
        String acs = metadata.replaceAll("(?s).*Location=\"([^\"]+)\".*", "$1");
        String[] started = start("saml");
        Map<String, String> params = query(started[0]);
        assertThat(started[0]).startsWith(IDP + "/sso?SAMLRequest=");
        byte[] deflated = Base64.getDecoder().decode(params.get("SAMLRequest"));
        String request = new String(new InflaterInputStream(new ByteArrayInputStream(deflated), new Inflater(true)).readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(request).contains("AssertionConsumerServiceURL=\"" + acs + "\"");
        String requestId = request.replaceAll("(?s).*\\sID=\"([^\"]+)\".*", "$1");
        return new SamlSetup(requestId, params.get("RelayState"), started[1], acs, entityId);
    }

    private static String samlXml(SamlSetup s, String assertionId, String email, String audience) {
        Instant now = Instant.now();
        return "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\" xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\""
                + " ID=\"_r" + UUID.randomUUID() + "\" Version=\"2.0\" IssueInstant=\"" + now + "\" Destination=\"" + s.acs()
                + "\" InResponseTo=\"" + s.requestId() + "\"><saml:Issuer>" + IDP + "</saml:Issuer>"
                + "<samlp:Status><samlp:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/></samlp:Status>"
                + "<saml:Assertion ID=\"" + assertionId + "\" Version=\"2.0\" IssueInstant=\"" + now + "\"><saml:Issuer>" + IDP + "</saml:Issuer>"
                + "<saml:Subject><saml:NameID Format=\"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\">emp-7</saml:NameID>"
                + "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><saml:SubjectConfirmationData NotOnOrAfter=\""
                + now.plusSeconds(300) + "\" Recipient=\"" + s.acs() + "\" InResponseTo=\"" + s.requestId() + "\"/></saml:SubjectConfirmation></saml:Subject>"
                + "<saml:Conditions NotBefore=\"" + now.minusSeconds(60) + "\" NotOnOrAfter=\"" + now.plusSeconds(300) + "\">"
                + "<saml:AudienceRestriction><saml:Audience>" + audience + "</saml:Audience></saml:AudienceRestriction></saml:Conditions>"
                + "<saml:AttributeStatement><saml:Attribute Name=\"email\"><saml:AttributeValue>" + email + "</saml:AttributeValue></saml:Attribute>"
                + "<saml:Attribute Name=\"displayName\"><saml:AttributeValue>Carol Corp</saml:AttributeValue></saml:Attribute></saml:AttributeStatement>"
                + "</saml:Assertion></samlp:Response>";
    }

    /** Signs the assertion with {@code keys} (an enveloped signature, as IdPs do). */
    private static Document sign(String xml, KeyPair keys) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element assertion = (Element) doc.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Assertion").item(0);
        assertion.setIdAttribute("ID", true);
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
        Reference ref = fac.newReference("#" + assertion.getAttribute("ID"), fac.newDigestMethod(DigestMethod.SHA256, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        fac.newTransform(CanonicalizationMethod.EXCLUSIVE, (TransformParameterSpec) null)), null, null);
        SignedInfo info = fac.newSignedInfo(fac.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod("http://www.w3.org/2001/04/xmldsig-more#rsa-sha256", null), List.of(ref));
        Element subject = (Element) assertion.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Subject").item(0);
        fac.newXMLSignature(info, null).sign(new DOMSignContext(keys.getPrivate(), assertion, subject));
        return doc;
    }

    private static String encode(Document doc) throws Exception {
        StringWriter out = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(out));
        return Base64.getEncoder().encodeToString(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Posts to the ACS and follows through to the app; returns the outcome fragment. */
    private String postSaml(SamlSetup s, String encoded) throws Exception {
        MockHttpServletResponse acs = mvc.perform(post("/api/auth/oauth/saml/acs").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("SAMLResponse", encoded).param("RelayState", s.relayState())).andExpect(status().isSeeOther()).andReturn().getResponse();
        URI next = URI.create(acs.getHeader("Location"));
        var request = get(next.getPath());
        for (Map.Entry<String, String> e : (next.getRawQuery() == null ? Map.<String, String>of() : query("http://x/?" + next.getRawQuery())).entrySet()) {
            request.param(e.getKey(), e.getValue());
        }
        return fragment(mvc.perform(request.cookie(new Cookie("fj_oauth", s.nonce()))).andReturn().getResponse());
    }

    @Test
    void samlSignInChecksSignatureAudienceAndReplay() throws Exception {
        theAdmin();
        SamlSetup s = startSaml();
        String good = encode(sign(samlXml(s, "_a" + UUID.randomUUID(), "carol@corp.example", s.entityId()), IDP_KEYS));
        String outcome = postSaml(s, good);
        assertThat(outcome).startsWith("token=");
        assertThat(me(outcome.substring(6)).toString()).contains("carol@corp.example").contains("Carol Corp");

        // The same response again: already used.
        assertThat(postSaml(s, good)).contains("could not be verified");

        // Signed by someone else.
        SamlSetup s2 = startSaml();
        assertThat(postSaml(s2, encode(sign(samlXml(s2, "_b" + UUID.randomUUID(), "carol@corp.example", s2.entityId()), ATTACKER_KEYS))))
                .contains("could not be verified");

        // Changed after signing.
        SamlSetup s3 = startSaml();
        Document tampered = sign(samlXml(s3, "_c" + UUID.randomUUID(), "carol@corp.example", s3.entityId()), IDP_KEYS);
        tampered.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue").item(0).setTextContent("admin@corp.example");
        assertThat(postSaml(s3, encode(tampered))).contains("could not be verified");

        // Not signed at all.
        SamlSetup s4 = startSaml();
        String unsigned = Base64.getEncoder().encodeToString(samlXml(s4, "_d" + UUID.randomUUID(), "x@corp.example", s4.entityId())
                .getBytes(StandardCharsets.UTF_8));
        assertThat(postSaml(s4, unsigned)).contains("could not be verified");

        // For another service.
        SamlSetup s5 = startSaml();
        assertThat(postSaml(s5, encode(sign(samlXml(s5, "_e" + UUID.randomUUID(), "carol@corp.example", "https://other.example"), IDP_KEYS))))
                .contains("could not be verified");

        // Signature wrapping: a forged assertion with the signed assertion's ID.
        SamlSetup s6 = startSaml();
        String id = "_f" + UUID.randomUUID();
        Document wrapped = sign(samlXml(s6, id, "carol@corp.example", s6.entityId()), IDP_KEYS);
        Element signedAssertion = (Element) wrapped.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "Assertion").item(0);
        Element extensions = wrapped.createElementNS("urn:oasis:names:tc:SAML:2.0:protocol", "samlp:Extensions");
        wrapped.getDocumentElement().insertBefore(extensions, wrapped.getDocumentElement().getFirstChild());
        Element forged = (Element) signedAssertion.cloneNode(true);
        forged.getElementsByTagNameNS("urn:oasis:names:tc:SAML:2.0:assertion", "AttributeValue").item(0).setTextContent("boss@corp.example");
        forged.removeChild(forged.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature").item(0));
        extensions.appendChild(signedAssertion);
        wrapped.getDocumentElement().appendChild(forged);
        assertThat(postSaml(s6, encode(wrapped))).contains("could not be verified");

        // A response for a different sign-in attempt.
        SamlSetup s7 = startSaml();
        SamlSetup other = new SamlSetup("_someoneelse", s7.relayState(), s7.nonce(), s7.acs(), s7.entityId());
        assertThat(postSaml(s7, encode(sign(samlXml(other, "_g" + UUID.randomUUID(), "carol@corp.example", s7.entityId()), IDP_KEYS))))
                .contains("could not be verified");

        // XML with a DOCTYPE (entity expansion) is refused outright.
        SamlSetup s8 = startSaml();
        String xxe = Base64.getEncoder().encodeToString(("<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<samlp:Response xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\">&x;</samlp:Response>").getBytes(StandardCharsets.UTF_8));
        assertThat(postSaml(s8, xxe)).contains("could not be verified");
    }

    // ---- LDAP -----------------------------------------------------------------------------------------------------

    @Test
    void ldapSignInCreatesAndLinksAccounts() throws Exception {
        theAdmin();
        JsonNode first = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("login", "dave", "password", "dave-pass"))).andExpect(status().isOk()));
        String token = first.get("token").asText();
        JsonNode user = me(token);
        assertThat(user.toString()).contains("dave@corp.example").contains("Dave Directory");

        JsonNode again = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("login", "dave", "password", "dave-pass"))).andExpect(status().isOk()));
        assertThat(me(again.get("token").asText()).path("user").path("id").asLong(-1))
                .isEqualTo(user.path("user").path("id").asLong(-2)).isPositive();

        for (String[] attempt : List.of(new String[]{"dave", "wrong"}, new String[]{"dave", ""}, new String[]{"*", "dave-pass"},
                new String[]{"dave)(uid=*", "dave-pass"}, new String[]{"nomail", "nomail-pass"})) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("login", attempt[0], "password", attempt[1]))))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(400, 401));
        }
    }

    // ---- SCIM -----------------------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions scim(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                                                    String token, Object body) throws Exception {
        request.header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType("application/scim+json").content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    @Test
    void scimProvisionsDeactivatesAndSyncsGroups() throws Exception {
        Account admin = theAdmin();
        scim(get("/scim/v2/Users"), "nope", null).andExpect(status().isUnauthorized());
        String token = read(perform(post("/api/admin/security/scim-token"), admin).andExpect(status().isOk())).get("token").asText();
        scim(get("/scim/v2/Users"), "wrong", null).andExpect(status().isUnauthorized());
        scim(get("/scim/v2/ServiceProviderConfig"), token, null).andExpect(status().isOk());

        // Someone who signed up already is linked by email.
        Account frank = register("frank" + UUID.randomUUID().toString().substring(0, 6));
        JsonNode linked = read(scim(post("/scim/v2/Users"), token, Map.of("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"),
                "userName", "frank@corp.example", "externalId", "ext-frank", "name", Map.of("givenName", "Frank", "familyName", "Fields"),
                "emails", List.of(Map.of("value", frank.username() + "@example.com", "primary", true)), "active", true))
                .andExpect(status().isCreated()));
        assertThat(linked.get("id").asLong()).isEqualTo(frank.id());
        assertThat(linked.get("displayName").asText()).isEqualTo("Frank Fields");

        JsonNode created = read(scim(post("/scim/v2/Users"), token, Map.of("userName", "grace.hopper@corp.example",
                "displayName", "Grace Hopper", "emails", List.of(Map.of("value", "grace.hopper@corp.example")), "active", true))
                .andExpect(status().isCreated()));
        String graceId = created.get("id").asText();
        scim(post("/scim/v2/Users"), token, Map.of("userName", "grace.hopper@corp.example",
                "emails", List.of(Map.of("value", "g2@corp.example")))).andExpect(status().isConflict());

        JsonNode found = read(scim(get("/scim/v2/Users").param("filter", "userName eq \"grace.hopper@corp.example\""), token, null)
                .andExpect(status().isOk()));
        assertThat(found.get("totalResults").asInt()).isEqualTo(1);
        assertThat(found.get("Resources").get(0).get("id").asText()).isEqualTo(graceId);
        scim(get("/scim/v2/Users").param("filter", "title co \"x\""), token, null).andExpect(status().isBadRequest());

        // Deactivating (Entra ID style) signs the person out and blocks sign-in.
        perform(get("/api/auth/me"), frank).andExpect(status().isOk());
        scim(patch("/scim/v2/Users/" + frank.id()), token, Map.of("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:PatchOp"),
                "Operations", List.of(Map.of("op", "Replace", "path", "active", "value", "False")))).andExpect(status().isOk());
        perform(get("/api/auth/me"), frank).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login", frank.username(), "password", PASSWORD)))
                .andExpect(status().isForbidden());
        // Reactivating (Okta style) lets them back in.
        scim(patch("/scim/v2/Users/" + frank.id()), token, Map.of("Operations", List.of(Map.of("op", "replace", "value", Map.of("active", true)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login", frank.username(), "password", PASSWORD)))
                .andExpect(status().isOk());

        // Groups become teams.
        JsonNode group = read(scim(post("/scim/v2/Groups"), token, Map.of("displayName", "Platform Engineers",
                "members", List.of(Map.of("value", graceId)))).andExpect(status().isCreated()));
        String groupId = group.get("id").asText();
        scim(patch("/scim/v2/Groups/" + groupId), token, Map.of("Operations", List.of(Map.of("op", "add", "path", "members",
                "value", List.of(Map.of("value", String.valueOf(frank.id())))))));
        JsonNode team = read(scim(get("/scim/v2/Groups/" + groupId), token, null));
        assertThat(team.get("members").size()).isEqualTo(2);
        scim(patch("/scim/v2/Groups/" + groupId), token, Map.of("Operations", List.of(Map.of("op", "remove",
                "path", "members[value eq \"" + graceId + "\"]"))));
        assertThat(read(scim(get("/scim/v2/Groups/" + groupId), token, null)).get("members").size()).isEqualTo(1);
        assertThat(getJson("/api/teams", admin).toString()).contains("Platform Engineers");
        scim(delete("/scim/v2/Groups/" + groupId), token, null).andExpect(status().isNoContent());

        // DELETE deactivates; revoking the token turns SCIM off.
        scim(delete("/scim/v2/Users/" + graceId), token, null).andExpect(status().isNoContent());
        assertThat(read(scim(get("/scim/v2/Users/" + graceId), token, null)).get("active").asBoolean()).isFalse();
        perform(delete("/api/admin/security/scim-token"), admin).andExpect(status().isOk());
        scim(get("/scim/v2/Users"), token, null).andExpect(status().isUnauthorized());
    }

    // ---- Policy ---------------------------------------------------------------------------------------------------

    private void savePolicy(Account admin, Map<String, Object> changes, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("ssoRequired", false, "sessionHours", 1, "idleMinutes", 0,
                "ipAllowlist", List.of(), "loginAlerts", true));
        body.putAll(changes);
        mvc.perform(put("/api/admin/security").header("Authorization", "Bearer " + admin.token())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(expected));
    }

    @Test
    void requireSsoSessionLengthAndIpAllowlist() throws Exception {
        Account admin = theAdmin();
        Account member = register();
        JsonNode overview = getJson("/api/admin/security", admin);
        assertThat(overview.get("ssoAvailable").asBoolean()).isTrue();
        assertThat(overview.get("signIn").toString()).contains("OpenID Connect (Okta)").contains("SAML").contains("LDAP");
        perform(get("/api/admin/security"), member).andExpect(status().isForbidden());

        savePolicy(admin, Map.of("ssoRequired", true), 200);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login", member.username(), "password", PASSWORD)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login", admin.username(), "password", PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("username", "latecomer", "email", "late@example.com", "password", PASSWORD))).andExpect(status().isForbidden());
        savePolicy(admin, Map.of(), 200);

        // Session length.
        savePolicy(admin, Map.of("sessionHours", 5), 200);
        String token = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("login", member.username(), "password", PASSWORD)))).get("token").asText();
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(5 * 3600);
        savePolicy(admin, Map.of("sessionHours", 0), 400);
        savePolicy(admin, Map.of("idleMinutes", 5), 400);

        // IP allowlist.
        savePolicy(admin, Map.of("ipAllowlist", List.of("10.0.0.0/8")), 400); // would lock the admin out
        savePolicy(admin, Map.of("ipAllowlist", List.of("not-an-ip")), 400);
        savePolicy(admin, Map.of("ipAllowlist", List.of("127.0.0.1", "10.0.0.0/8", "2001:db8::/32")), 200);
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + member.token())).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + member.token()).header("X-Forwarded-For", "10.20.30.40"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + member.token()).header("X-Forwarded-For", "2001:db8:1::5"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + member.token()).header("X-Forwarded-For", "203.0.113.9"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", "203.0.113.9")
                .content(body("login", member.username(), "password", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(get("/api/health").header("X-Forwarded-For", "203.0.113.9")).andExpect(status().isOk());

        assertThat(SecurityPolicy.parse("192.168.0.0/16").contains(SecurityPolicy.address("192.168.44.1"))).isTrue();
        assertThat(SecurityPolicy.parse("192.168.0.0/16").contains(SecurityPolicy.address("192.169.0.1"))).isFalse();
        assertThat(SecurityPolicy.parse("10.0.0.0/8").contains(SecurityPolicy.address("::ffff:10.1.2.3"))).isTrue();
    }

    @Test
    void adminsCanSuspendAndReactivate() throws Exception {
        Account admin = theAdmin();
        Account member = register();
        perform(post("/api/admin/users/" + member.id() + "/suspend"), admin).andExpect(status().isOk());
        perform(get("/api/auth/me"), member).andExpect(status().isUnauthorized());
        JsonNode users = getJson("/api/admin", admin).get("users");
        assertThat(users.toString()).contains("SUSPENDED");
        perform(post("/api/admin/users/" + admin.id() + "/suspend"), admin).andExpect(status().isBadRequest());
        perform(post("/api/admin/users/" + member.id() + "/reactivate"), admin).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body("login", member.username(), "password", PASSWORD)))
                .andExpect(status().isOk());
    }

    // ---- Sign-in alerts -------------------------------------------------------------------------------------------

    @Test
    void newDevicesAndRepeatedFailuresRaiseAlerts() throws Exception {
        theAdmin();
        Account member = register();
        String chrome = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36";
        String firefox = "Mozilla/5.0 (X11; Linux x86_64; rv:130.0) Gecko/20100101 Firefox/130.0";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("User-Agent", chrome)
                    .header("X-Forwarded-For", "198.51.100." + (10 + i)).content(body("login", member.username(), "password", PASSWORD)))
                    .andExpect(status().isOk());
        }
        String alerts = getJson("/api/notifications", member).toString();
        // Registration counted as the first device; a new browser now, then the same one again from the same network.
        assertThat(alerts.split("New sign-in to your account", -1).length - 1).isEqualTo(1);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).header("User-Agent", firefox)
                .header("X-Forwarded-For", "203.0.113.50").content(body("login", member.username(), "password", PASSWORD)))
                .andExpect(status().isOk());
        alerts = getJson("/api/notifications", member).toString();
        assertThat(alerts).contains("Firefox on Linux from 203.0.113.50");

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(body("login", member.username(), "password", "wrong-" + i))).andExpect(status().isUnauthorized());
        }
        assertThat(getJson("/api/notifications", member).toString()).contains("wrong password for your account 5 times");
    }
}
