package com.fakejira;

import com.fakejira.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Without APP_BASE_URL, links use the address admins reach the app at — never one a non-admin supplies. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.base-url=")
class SiteUrlTest extends ApiTestSupport {

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void linksFollowTheAddressAdminsUseBehindTheProxy() throws Exception {
        Account admin = register();
        tx.executeWithoutResult(s -> users.findById(admin.id()).orElseThrow().setAdmin(true));
        Account member = register();

        // Caddy forwards the original scheme; the Host header carries the public name.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + admin.token())
                        .header("Host", "fakejira.example.org").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk());

        // A non-admin cannot redirect links (e.g. password reset emails) with a forged Host header.
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + member.token())
                        .header("Host", "evil.example.com").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk());

        perform(post("/api/invites"), admin, "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.startsWith("https://fakejira.example.org/register?invite=")));
        perform(get("/api/admin"), admin)
                .andExpect(jsonPath("$.siteUrl").value("https://fakejira.example.org"))
                .andExpect(jsonPath("$.siteUrlConfigured").value(false));
    }
}
