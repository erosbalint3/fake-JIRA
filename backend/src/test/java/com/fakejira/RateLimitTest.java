package com.fakejira;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.rate-limit.enabled=true")
class RateLimitTest extends ApiTestSupport {

    @Test
    void loginIsLimitedPerClientIp() throws Exception {
        String login = body("login", "nobody-" + System.nanoTime(), "password", "Wrong#123");
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/auth/login").header("CF-Connecting-IP", "203.0.113.7")
                            .contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").header("CF-Connecting-IP", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // A different client is not affected.
        mvc.perform(post("/api/auth/login").header("CF-Connecting-IP", "203.0.113.8")
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginIsLimitedPerAccountAcrossIps() throws Exception {
        String login = body("login", "target-" + System.nanoTime(), "password", "Wrong#123");
        for (int i = 0; i < 20; i++) {
            mvc.perform(post("/api/auth/login").header("X-Forwarded-For", "198.51.100." + i + ", 10.0.0.1")
                            .contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void registrationIsLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            String name = uniqueName("rl");
            mvc.perform(post("/api/auth/register").header("CF-Connecting-IP", "203.0.113.50")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("username", name, "email", name + "@example.com", "password", PASSWORD)))
                    .andExpect(status().isCreated());
        }
        String name = uniqueName("rl");
        mvc.perform(post("/api/auth/register").header("CF-Connecting-IP", "203.0.113.50")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", name, "email", name + "@example.com", "password", PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }
}
