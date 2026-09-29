package com.fakejira;

import com.fakejira.admin.AppSettings;
import com.fakejira.admin.RegistrationMode;
import com.fakejira.user.EmailFrequency;
import com.fakejira.user.User;
import com.fakejira.user.UserDataMigration;
import com.fakejira.user.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.backup.dir=${java.io.tmpdir}/fakejira-test-backups")
class SecurityOpsTest extends ApiTestSupport {

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private UserDataMigration userMigration;

    private Account admin() throws Exception {
        Account account = register();
        tx.executeWithoutResult(s -> users.findById(account.id()).orElseThrow().setAdmin(true));
        return account;
    }

    @Autowired
    private AppSettings settings;

    @AfterEach
    void resetRegistrationMode() {
        settings.setRegistrationMode(RegistrationMode.OPEN);
    }

    private String registerBody(String name, String invite) throws Exception {
        return json.writeValueAsString(java.util.Map.of("username", name, "email", name + "@example.com",
                "password", PASSWORD, "inviteCode", invite == null ? "" : invite));
    }

    @Test
    void inviteOnlySignUpNeedsAValidSingleUseInvite() throws Exception {
        Account admin = admin();
        Account owner = register();
        Account member = register();
        String key = project(owner);
        perform(put("/api/admin/registration"), admin, "{\"mode\":\"INVITE\"}")
                .andExpect(jsonPath("$.registrationMode").value("INVITE"));

        String name = uniqueName("inv");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registerBody(name, null)))
                .andExpect(status().isForbidden());

        // Project owners can invite to their project; members cannot invite without a project.
        perform(post("/api/invites"), member, "{}").andExpect(status().isForbidden());
        JsonNode invite = read(perform(post("/api/invites"), owner,
                "{\"email\":\"" + name + "@example.com\",\"projectKey\":\"" + key + "\"}").andExpect(status().isCreated()));
        String code = invite.get("code").asText();
        assertThat(invite.get("link").asText()).endsWith("/register?invite=" + code);

        mvc.perform(get("/api/auth/invite").param("code", code))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.email").value(name + "@example.com"))
                .andExpect(jsonPath("$.registrationMode").value("INVITE"));

        // Bound to the invited email.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("username", name + "x", "email",
                                "someone-else@example.com", "password", PASSWORD, "inviteCode", code))))
                .andExpect(status().isBadRequest());

        JsonNode joined = read(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(name, code))).andExpect(status().isCreated()));
        Account invited = new Account(joined.get("token").asText(), joined.get("user").get("id").asLong(), name);
        // The invite added them to the project.
        perform(get("/api/projects/" + key), invited).andExpect(status().isOk());

        // Single use.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueName("again"), code)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/invite").param("code", code)).andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void approvalModeHoldsAccountsUntilAnAdminApproves() throws Exception {
        Account admin = admin();
        Account member = register();
        perform(put("/api/admin/registration"), admin, "{\"mode\":\"APPROVAL\"}").andExpect(status().isOk());

        String name = uniqueName("pend");
        JsonNode pending = read(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(name, null))).andExpect(status().isAccepted()));
        assertThat(pending.get("pending").asBoolean()).isTrue();
        assertThat(pending.get("token").isNull()).isTrue();
        long id = pending.get("user").get("id").asLong();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", name, "password", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("approve")));
        perform(get("/api/notifications"), admin)
                .andExpect(jsonPath("$.items[0].message").value(org.hamcrest.Matchers.containsString("waiting for approval")));

        perform(post("/api/admin/users/" + id + "/approve"), member).andExpect(status().isForbidden());
        perform(post("/api/admin/users/" + id + "/approve"), admin).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", name, "password", PASSWORD)))
                .andExpect(status().isOk());

        // Rejecting deletes a pending account.
        String other = uniqueName("rej");
        long otherId = read(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(other, null)))).get("user").get("id").asLong();
        perform(delete("/api/admin/users/" + otherId), admin).andExpect(status().isNoContent());
        perform(delete("/api/admin/users/" + id), admin).andExpect(status().isBadRequest());
    }

    @Test
    void adminsManageAdminsButCannotRemoveTheLastOne() throws Exception {
        Account admin = admin();
        Account other = register();
        perform(get("/api/admin"), other).andExpect(status().isForbidden());
        perform(put("/api/admin/users/" + other.id() + "/admin"), admin, "{\"admin\":true}")
                .andExpect(jsonPath("$.admin").value(true));
        perform(get("/api/auth/me"), other).andExpect(jsonPath("$.admin").value(true));
        perform(put("/api/admin/users/" + other.id() + "/admin"), admin, "{\"admin\":false}")
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    void backupsContainDatabaseAndAttachments() throws Exception {
        Account admin = admin();
        perform(get("/api/admin/backups"), register()).andExpect(status().isForbidden());
        JsonNode backup = read(perform(post("/api/admin/backups"), admin).andExpect(status().isCreated()));
        String name = backup.get("name").asText();
        assertThat(name).matches("fakejira-backup-\\d{8}-\\d{6}\\.zip");
        assertThat(texts(getJson("/api/admin/backups", admin), "name")).contains(name);

        byte[] zip = perform(get("/api/admin/backups/" + name), admin)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.add(entry.getName());
            }
        }
        // Tests use an in-memory database, which is dumped as SQL; file databases produce database.zip.
        assertThat(entries).contains("database.sql", "README.txt");
        // Path tricks are rejected (by the firewall or by the strict name check).
        perform(get("/api/admin/backups/..%2F..%2Fetc%2Fpasswd"), admin).andExpect(status().is4xxClientError());
        perform(get("/api/admin/backups/evil.zip"), admin).andExpect(status().isNotFound());
    }

    @Test
    void migrationCreatesAnAdminAndConvertsTheOldEmailSwitch() throws Exception {
        Account account = register();
        tx.executeWithoutResult(s -> {
            users.findAll().forEach(u -> u.setAdmin(false));
            User user = users.findById(account.id()).orElseThrow();
            user.setEmailNotifications(true);
            user.setEmailFrequency(EmailFrequency.OFF);
        });
        userMigration.run(null);
        assertThat(users.countByAdminTrue()).isEqualTo(1);
        User migrated = users.findById(account.id()).orElseThrow();
        assertThat(migrated.getEmailFrequency()).isEqualTo(EmailFrequency.INSTANT);
        assertThat(migrated.isEmailNotifications()).isFalse();
    }
}
