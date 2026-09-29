package com.fakejira;

import com.fakejira.mail.InboundMail;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reply-by-email and email-to-task through the inbound webhook. */
@SpringBootTest(properties = {"app.mail.inbound.address=jira@example.com", "app.mail.inbound.secret=inbound-s3cret"})
@AutoConfigureMockMvc
class InboundMailTest extends ApiTestSupport {

    @Autowired
    private InboundMail inbound;

    private org.springframework.test.web.servlet.ResultActions email(String secret, Map<String, Object> mail) throws Exception {
        return mvc.perform(post("/api/inbound/email").header("X-Inbound-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mail)));
    }

    @Test
    void repliesBecomeCommentsAndMailCreatesTasks() throws Exception {
        Account alice = register();
        Account bob = register();
        String key = project(alice, bob);
        long id = task(alice, key, "Needs input").get("id").asLong();
        String reply = inbound.replyAddress(id, bob.id()).orElseThrow();
        assertThat(reply).startsWith("jira+t" + id + "." + bob.id() + ".").endsWith("@example.com");
        String bobEmail = bob.username() + "@example.com";

        email("wrong", Map.of("from", bobEmail, "to", reply, "text", "hi")).andExpect(status().isNotFound());

        String text = "Looks good to me!\n\nOn Tue, Alice wrote:\n> Needs input\n> more";
        email("inbound-s3cret", Map.of("from", "Bob <" + bobEmail + ">", "to", "Team <" + reply + ">", "subject", "Re: x", "text", text))
                .andExpect(jsonPath("$.outcome").value("COMMENTED"));
        JsonNode comments = getJson("/api/tasks/" + id + "/comments", alice);
        assertThat(comments.get(comments.size() - 1).get("body").asText()).isEqualTo("Looks good to me!");
        assertThat(comments.get(comments.size() - 1).get("author").get("id").asLong()).isEqualTo(bob.id());

        // Someone else using Bob's reply address, or a tampered signature, is ignored.
        email("inbound-s3cret", Map.of("from", alice.username() + "@example.com", "to", reply, "text", "spoof"))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        String tampered = reply.replaceFirst("\\.[0-9a-f]{12}@", ".000000000000@");
        email("inbound-s3cret", Map.of("from", bobEmail, "to", tampered, "text", "spoof"))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));

        // Email to jira+key@ creates a task with the subject as its title.
        email("inbound-s3cret", Map.of("from", bobEmail, "to", java.util.List.of("other@x.org", "jira+" + key.toLowerCase() + "@example.com"),
                "subject", "Fwd: Printer on fire", "text", "It is really on fire.\n-- \nBob"))
                .andExpect(jsonPath("$.outcome").value("CREATED"));
        JsonNode created = getJson("/api/tasks?project=" + key + "&q=Printer", alice).get(0);
        assertThat(created.get("title").asText()).isEqualTo("Printer on fire");
        assertThat(created.get("description").asText()).isEqualTo("It is really on fire.");
        assertThat(created.get("reporter").get("id").asLong()).isEqualTo(bob.id());
        assertThat(created.get("labels").get(0).asText()).isEqualTo("email");

        email("inbound-s3cret", Map.of("from", "stranger@example.org", "to", "jira+" + key + "@example.com", "subject", "Spam"))
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        assertThat(getJson("/api/projects/" + key + "/email-address", alice).get("address").asText())
                .isEqualTo("jira+" + key.toLowerCase() + "@example.com");
    }
}
