package com.fakejira.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.util.Hashtable;
import java.util.Optional;

/**
 * Password sign-in against a company LDAP / Active Directory server. The account is found either with a DN
 * pattern ({@code uid={0},ou=people,…}) or by searching with a service account; the person's own password is then
 * checked by binding as them. Values are escaped (RFC 4514/4515) and empty passwords are refused, so an anonymous
 * bind can never count as a sign-in.
 */
@Service
public class LdapDirectory {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectory.class);

    /** Someone the directory vouched for. {@code dn} identifies them for good. */
    public record Person(String dn, String login, String email, String name) {
    }

    private final String url;
    private final String dnPattern;
    private final String bindDn;
    private final String bindPassword;
    private final String searchBase;
    private final String searchFilter;
    private final String emailAttribute;
    private final String nameAttribute;
    private final String label;

    public LdapDirectory(@Value("${app.ldap.url:}") String url,
                         @Value("${app.ldap.user-dn-pattern:}") String dnPattern,
                         @Value("${app.ldap.bind-dn:}") String bindDn,
                         @Value("${app.ldap.bind-password:}") String bindPassword,
                         @Value("${app.ldap.user-search-base:}") String searchBase,
                         @Value("${app.ldap.user-search-filter:(|(uid={0})(mail={0})(sAMAccountName={0}))}") String searchFilter,
                         @Value("${app.ldap.email-attribute:mail}") String emailAttribute,
                         @Value("${app.ldap.name-attribute:cn}") String nameAttribute,
                         @Value("${app.ldap.label:Company directory}") String label) {
        this.url = url.trim();
        this.dnPattern = dnPattern.trim();
        this.bindDn = bindDn.trim();
        this.bindPassword = bindPassword;
        this.searchBase = searchBase.trim();
        this.searchFilter = searchFilter.trim();
        this.emailAttribute = emailAttribute;
        this.nameAttribute = nameAttribute;
        this.label = label;
    }

    public boolean enabled() {
        return !url.isEmpty() && (!dnPattern.isEmpty() || !searchBase.isEmpty());
    }

    public String label() {
        return label;
    }

    /** Checks the password with the directory; empty when the login or password is wrong (or LDAP is off/down). */
    public Optional<Person> authenticate(String login, String password) {
        if (!enabled() || login == null || login.isBlank() || password == null || password.isEmpty() || login.length() > 200) {
            return Optional.empty();
        }
        try {
            String dn = dnPattern.isEmpty() ? search(login.trim()) : dnPattern.replace("{0}", escapeDn(login.trim()));
            if (dn == null) {
                return Optional.empty();
            }
            LdapContext context = connect(dn, password);
            try {
                Attributes attributes = context.getAttributes(dn, new String[]{emailAttribute, nameAttribute, "uid", "sAMAccountName"});
                String email = value(attributes, emailAttribute);
                String uid = value(attributes, "uid");
                if (uid == null) uid = value(attributes, "sAMAccountName");
                return Optional.of(new Person(dn, uid != null ? uid : login.trim(), email, value(attributes, nameAttribute)));
            } finally {
                context.close();
            }
        } catch (javax.naming.AuthenticationException e) {
            return Optional.empty();
        } catch (NamingException e) {
            log.warn("LDAP sign-in failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String search(String login) throws NamingException {
        LdapContext context = connect(bindDn.isEmpty() ? null : bindDn, bindDn.isEmpty() ? null : bindPassword);
        try {
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setCountLimit(2);
            controls.setTimeLimit(5000);
            controls.setReturningAttributes(new String[]{"dn"});
            NamingEnumeration<SearchResult> results = context.search(searchBase, searchFilter.replace("{0}", escapeFilter(login)), controls);
            String dn = null;
            int count = 0;
            try {
                while (results.hasMore()) {
                    SearchResult result = results.next();
                    dn = result.getNameInNamespace();
                    count++;
                }
            } catch (javax.naming.SizeLimitExceededException e) {
                count = 2;
            }
            return count == 1 ? dn : null; // ambiguous logins do not sign anyone in
        } finally {
            context.close();
        }
    }

    private LdapContext connect(String principal, String password) throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "10000");
        env.put(Context.REFERRAL, "ignore");
        if (principal != null) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, principal);
            env.put(Context.SECURITY_CREDENTIALS, password);
        } else {
            env.put(Context.SECURITY_AUTHENTICATION, "none");
        }
        return new InitialLdapContext(env, null);
    }

    private static String value(Attributes attributes, String name) throws NamingException {
        Attribute attribute = attributes.get(name);
        return attribute == null || attribute.size() == 0 ? null : String.valueOf(attribute.get());
    }

    /** RFC 4515 escaping for a value inside a search filter. */
    static String escapeFilter(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\5c");
                case '*' -> out.append("\\2a");
                case '(' -> out.append("\\28");
                case ')' -> out.append("\\29");
                case '\0' -> out.append("\\00");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** RFC 4514 escaping for a value inside a DN. */
    static String escapeDn(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean edge = (i == 0 && (c == ' ' || c == '#')) || (i == value.length() - 1 && c == ' ');
            if (edge || ",+\"\\<>;=".indexOf(c) >= 0) {
                out.append('\\');
            }
            if (c == '\0') {
                out.append("\\00");
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
