package com.fakejira;

import com.fakejira.notification.DigestService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.mail.host=localhost", "app.base-url=https://jira.example.test"})
class DigestTest extends ApiTestSupport {

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private DigestService digests;

    @Test
    void dailyDigestBundlesNotificationsIntoOneEmail() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        perform(put("/api/profile/settings"), alice, "{\"emailFrequency\":\"DAILY\"}").andExpect(status().isOk());
        long id = task(alice, key, "Digest me").get("id").asLong();
        clearInvocations(mailSender);

        perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "IN_PROGRESS"));
        perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "DONE"));
        // No instant emails for digest users.
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));

        assertThat(digests.send(false)).isGreaterThanOrEqualTo(1);
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000).atLeastOnce()).send(sent.capture());
        SimpleMailMessage digest = sent.getAllValues().stream()
                .filter(m -> List.of(m.getTo()).contains(alice.username() + "@example.com")).findFirst().orElseThrow();
        assertThat(digest.getSubject()).contains("daily digest", "2 updates");
        assertThat(digest.getText()).contains("moved to in progress", "moved to done", "https://jira.example.test/tasks/" + id);

        // Nothing new: no second digest for alice.
        clearInvocations(mailSender);
        digests.send(false);
        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }
}
