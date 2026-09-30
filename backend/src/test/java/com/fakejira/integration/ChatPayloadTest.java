package com.fakejira.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Message formats for Teams and Mattermost incoming webhooks. */
class ChatPayloadTest {

    @Test
    void teamsAndMattermostPayloads() throws Exception {
        com.fakejira.mail.MailService mail = mock(com.fakejira.mail.MailService.class);
        when(mail.link("/tasks/1")).thenReturn("https://jira.example/tasks/1");
        ChatSender sender = new ChatSender(null, mail, null, new ObjectMapper(), false);
        ChatNotifier.ChatMessage message = new ChatNotifier.ChatMessage(1L, ChatEventType.COMMENT_ADDED, "ann_b", "commented on",
                "WEB-1 [urgent] fix", "/tasks/1", "Looks good @here");
        ObjectMapper json = new ObjectMapper();

        JsonNode mattermost = json.readTree(sender.payload(ChatHook.Kind.MATTERMOST, message));
        assertThat(mattermost.get("text").asText()).isEqualTo(
                "**ann\\_b** commented on [WEB-1 \\[urgent\\] fix](https://jira.example/tasks/1)\n\n> Looks good @​here");

        JsonNode teams = json.readTree(sender.payload(ChatHook.Kind.TEAMS, message));
        assertThat(teams.get("@type").asText()).isEqualTo("MessageCard");
        assertThat(teams.get("summary").asText()).isEqualTo("ann_b commented on WEB-1 [urgent] fix");
        assertThat(teams.get("text").asText()).contains("(https://jira.example/tasks/1)");
    }
}
