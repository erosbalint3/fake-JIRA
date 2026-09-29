package com.fakejira;

import com.fakejira.auth.PasswordPolicy;
import com.fakejira.auth.Totp;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sessions, two-factor sign-in, password policy, audit log, account export/deletion and comment editing. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSecurityTest extends ApiTestSupport {

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PasswordPolicy policy;

    @AfterEach
    void resetPolicy() {
        policy.setRules(new PasswordPolicy.Rules(8, true, true, true));
    }

    private Account admin() throws Exception {
        Account account = register();
        tx.executeWithoutResult(s -> users.findById(account.id()).orElseThrow().setAdmin(true));
        return account;
    }

    private JsonNode login(String login, String password) throws Exception {
        return read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("login", login, "password", password))).andExpect(status().isOk()));
    }

    private Account as(Account account, String token) {
        return new Account(token, account.id(), account.username());
    }

    @Test
    void devicesCanBeListedAndSignedOut() throws Exception {
        Account first = register();
        Account second = as(first, login(first.username(), PASSWORD).get("token").asText());

        JsonNode sessions = getJson("/api/profile/sessions", first);
        assertThat(sessions.size()).isEqualTo(2);
        String secondId = null;
        for (JsonNode s : sessions) {
            if (!s.get("current").asBoolean()) {
                secondId = s.get("id").asText();
            }
        }
        perform(delete("/api/profile/sessions/" + secondId), first).andExpect(status().isNoContent());
        perform(get("/api/auth/me"), second).andExpect(status().isUnauthorized());
        perform(get("/api/auth/me"), first).andExpect(status().isOk());

        Account third = as(first, login(first.username(), PASSWORD).get("token").asText());
        perform(post("/api/profile/sessions/sign-out-others"), first).andExpect(jsonPath("$.signedOut").value(1));
        perform(get("/api/auth/me"), third).andExpect(status().isUnauthorized());

        perform(post("/api/auth/logout"), first).andExpect(status().isNoContent());
        perform(get("/api/auth/me"), first).andExpect(status().isUnauthorized());
    }

    @Test
    void twoFactorSignInNeedsAFreshCodeOrARecoveryCode() throws Exception {
        Account account = register();
        JsonNode setup = read(perform(post("/api/profile/2fa/setup"), account).andExpect(status().isOk()));
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUrl").asText()).startsWith("otpauth://totp/FakeJIRA%3A" + account.username());
        perform(post("/api/profile/2fa/enable"), account, body("code", "000000")).andExpect(status().isBadRequest());
        long step = Totp.currentStep();
        JsonNode enabled = read(perform(post("/api/profile/2fa/enable"), account, body("code", Totp.code(secret, step)))
                .andExpect(status().isOk()));
        assertThat(enabled.get("recoveryCodes").size()).isEqualTo(10);
        String recovery = enabled.get("recoveryCodes").get(0).asText();

        // Password alone no longer signs in: it returns a challenge.
        JsonNode first = login(account.username(), PASSWORD);
        assertThat(first.get("token").isNull()).isTrue();
        String challenge = first.get("challenge").asText();
        mvc.perform(post("/api/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                        .content(body("challenge", challenge, "code", "123456")))
                .andExpect(status().isBadRequest());
        // The code used to enable 2FA cannot be replayed.
        mvc.perform(post("/api/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                        .content(body("challenge", challenge, "code", Totp.code(secret, step))))
                .andExpect(status().isBadRequest());
        // The challenge itself is not a sign-in token.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + challenge)).andExpect(status().isUnauthorized());

        JsonNode signedIn = read(mvc.perform(post("/api/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                .content(body("challenge", challenge, "code", Totp.code(secret, step + 1)))).andExpect(status().isOk()));
        assertThat(signedIn.get("token").asText()).isNotEmpty();

        // A recovery code works once.
        String challenge2 = login(account.username(), PASSWORD).get("challenge").asText();
        mvc.perform(post("/api/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                .content(body("challenge", challenge2, "code", recovery.toUpperCase()))).andExpect(status().isOk());
        String challenge3 = login(account.username(), PASSWORD).get("challenge").asText();
        mvc.perform(post("/api/auth/login/2fa").contentType(MediaType.APPLICATION_JSON)
                .content(body("challenge", challenge3, "code", recovery))).andExpect(status().isBadRequest());
        assertThat(getJson("/api/profile", account).get("recoveryCodesLeft").asInt()).isEqualTo(9);

        // An admin can turn it off for a lost phone.
        Account admin = admin();
        perform(post("/api/admin/users/" + account.id() + "/reset-2fa"), admin).andExpect(jsonPath("$.twoFactor").value(false));
        assertThat(login(account.username(), PASSWORD).get("token").asText()).isNotEmpty();
    }

    @Test
    void passwordPolicyAndForcedPasswordChange() throws Exception {
        Account admin = admin();
        Account user = register();
        String key = project(user);
        perform(put("/api/admin/password-policy"), admin,
                "{\"minLength\":12,\"upper\":false,\"digit\":true,\"special\":false}")
                .andExpect(jsonPath("$.minLength").value(12));
        mvc.perform(get("/api/auth/password-policy")).andExpect(jsonPath("$.minLength").value(12));
        String name = uniqueName("pol");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", name, "email", name + "@example.com", "password", "short1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("Password must be at least 12 characters long"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", name, "email", name + "@example.com", "password", "longenoughbutnodigit")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").value("Password needs a number"));

        perform(post("/api/admin/users/" + user.id() + "/require-password-change"), admin)
                .andExpect(jsonPath("$.mustChangePassword").value(true));
        // Their session was ended; after signing in again, changes are blocked until the password changes.
        Account again = as(user, login(user.username(), PASSWORD).get("token").asText());
        perform(get("/api/auth/me"), again).andExpect(jsonPath("$.mustChangePassword").value(true));
        perform(post("/api/tasks"), again, body("projectKey", key, "title", "x", "priority", "LOW"))
                .andExpect(status().isForbidden());
        perform(put("/api/profile/password"), again, body("currentPassword", PASSWORD, "newPassword", "Brand-new-pass-99"))
                .andExpect(status().isNoContent());
        perform(post("/api/tasks"), again, body("projectKey", key, "title", "x", "priority", "LOW"))
                .andExpect(status().isCreated());
    }

    @Test
    void auditLogRecordsSecurityEvents() throws Exception {
        Account admin = admin();
        String name = uniqueName("aud");
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "203.0.113.7")
                .content(body("login", name, "password", "Nope#1234"))).andExpect(status().isUnauthorized());
        perform(put("/api/admin/registration"), admin, "{\"mode\":\"OPEN\"}").andExpect(status().isOk());

        JsonNode failures = getJson("/api/admin/audit?action=login.failure&q=" + name, admin);
        assertThat(failures.get("total").asInt()).isEqualTo(1);
        assertThat(failures.get("items").get(0).get("ip").asText()).isEqualTo("203.0.113.7");
        assertThat(getJson("/api/admin/audit", admin).get("actions").size()).isGreaterThan(0);
        JsonNode all = getJson("/api/admin/audit?q=" + admin.username(), admin);
        assertThat(texts(all.get("items"), "action")).contains("admin.registration_mode", "account.register");

        Account plain = register();
        perform(get("/api/admin/audit"), plain).andExpect(status().isForbidden());
    }

    @Test
    void accountsCanBeExportedAndDeleted() throws Exception {
        Account owner = register();
        Account other = register();
        String shared = project(owner, other);
        String solo = project(owner);
        JsonNode task = task(owner, shared, "Owned by someone who leaves");
        perform(post("/api/tasks/" + task.get("id").asLong() + "/comments"), owner, body("body", "Bye"))
                .andExpect(status().isCreated());

        JsonNode export = read(perform(get("/api/profile/export"), owner).andExpect(status().isOk()));
        assertThat(export.get("profile").get("username").asText()).isEqualTo(owner.username());
        assertThat(texts(export.get("tasksReported"), "key")).contains(task.get("key").asText());
        assertThat(export.get("comments").size()).isEqualTo(1);

        perform(delete("/api/profile"), owner, body("password", "wrong")).andExpect(status().isBadRequest());
        perform(delete("/api/profile"), owner, body("password", PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(shared)));
        perform(put("/api/projects/" + shared + "/owner"), owner, "{\"userId\":" + other.id() + "}")
                .andExpect(jsonPath("$.owner.id").value(other.id()));
        perform(delete("/api/profile"), owner, body("password", PASSWORD)).andExpect(status().isNoContent());

        perform(get("/api/auth/me"), owner).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body("login", owner.username(), "password", PASSWORD))).andExpect(status().isUnauthorized());
        perform(get("/api/projects/" + solo), other).andExpect(status().isNotFound());
        // History keeps an anonymous author.
        JsonNode comments = getJson("/api/tasks/" + task.get("id").asLong() + "/comments", other);
        assertThat(comments.get(0).get("author").get("displayName").asText()).isEqualTo("Deleted user");
        assertThat(texts(getJson("/api/projects/" + shared, other).get("members"), "username"))
                .doesNotContain(owner.username());
    }

    @Test
    void commentsCanBeEditedByTheirAuthorAndDeleted() throws Exception {
        Account owner = register();
        Account member = register();
        String key = project(owner, member);
        long taskId = task(owner, key, "Discuss").get("id").asLong();
        long commentId = read(perform(post("/api/tasks/" + taskId + "/comments"), member, body("body", "Frist"))
                .andExpect(status().isCreated())).get("id").asLong();

        perform(put("/api/tasks/" + taskId + "/comments/" + commentId), owner, body("body", "hijack"))
                .andExpect(status().isForbidden());
        perform(put("/api/tasks/" + taskId + "/comments/" + commentId), member, body("body", "First, @" + owner.username()))
                .andExpect(jsonPath("$.body").value("First, @" + owner.username()))
                .andExpect(jsonPath("$.editedAt").isNotEmpty());
        // The owner was newly mentioned by the edit.
        assertThat(texts(getJson("/api/notifications", owner).get("items"), "message").toString()).contains("mentioned you");

        long second = read(perform(post("/api/tasks/" + taskId + "/comments"), member, body("body", "spam"))
                .andExpect(status().isCreated())).get("id").asLong();
        perform(delete("/api/tasks/" + taskId + "/comments/" + second), owner).andExpect(status().isNoContent());
        assertThat(getJson("/api/tasks/" + taskId + "/comments", member).size()).isEqualTo(1);
    }
}
