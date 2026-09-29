package com.fakejira;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.mail.host=localhost", "app.base-url=https://jira.example.test"})
class PasswordResetTest extends ApiTestSupport {

    @MockitoBean
    private JavaMailSender mailSender;

    @Test
    void resetLinkIsEmailedAndWorksOnce() throws Exception {
        Account account = register();
        clearInvocations(mailSender);

        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", account.username() + "@example.com")))
                .andExpect(status().isNoContent());
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(sent.capture());
        Matcher link = Pattern.compile("https://jira\\.example\\.test/reset-password\\?token=([\\w-]+)")
                .matcher(sent.getValue().getText());
        assertThat(link.find()).isTrue();
        String token = link.group(1);

        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body("token", token, "newPassword", "weak")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body("token", token, "newPassword", "Brand#New1")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("login", account.username(), "password", "Brand#New1")))
                .andExpect(status().isOk());
        // Single use.
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body("token", token, "newPassword", "Another#New2")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEmailLooksTheSameAndSendsNothing() throws Exception {
        clearInvocations(mailSender);
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", "nobody-" + System.nanoTime() + "@example.com")))
                .andExpect(status().isNoContent());
        verify(mailSender, after(500).never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    @Test
    void notificationEmailsOnlyWhenOptedIn() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Mail me").get("id").asLong();
        perform(post("/api/tasks/" + id + "/accept"), bob).andExpect(status().isOk());
        clearInvocations(mailSender);

        // Alice has not opted in: no email.
        perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "IN_PROGRESS")).andExpect(status().isOk());
        verify(mailSender, after(500).never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        perform(put("/api/profile/settings"), alice, "{\"emailFrequency\":\"INSTANT\"}").andExpect(status().isOk());
        perform(patch("/api/tasks/" + id + "/status"), bob, body("status", "DONE")).andExpect(status().isOk());
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly(alice.username() + "@example.com");
        assertThat(sent.getValue().getSubject()).contains("moved to done", key + "-1");
        assertThat(sent.getValue().getText()).contains("https://jira.example.test/tasks/" + id);
    }
}
