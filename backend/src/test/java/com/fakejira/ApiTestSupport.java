package com.fakejira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared helpers for API integration tests. */
public abstract class ApiTestSupport {

    protected static final String PASSWORD = "Secret#123";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    /** A registered user: bearer token plus ids. */
    protected record Account(String token, long id, String username) {
    }

    protected Account register() throws Exception {
        return register(uniqueName("user"));
    }

    protected Account register(String username) throws Exception {
        JsonNode response = read(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("username", username, "email", username + "@example.com", "password", PASSWORD)))
                .andExpect(status().isCreated()));
        return new Account(response.get("token").asText(), response.get("user").get("id").asLong(), username);
    }

    /** Creates a project owned by {@code owner} with the given extra members, returns its key. */
    protected String project(Account owner, Account... members) throws Exception {
        String key = ("P" + UUID.randomUUID().toString().replace("-", "")).substring(0, 8).toUpperCase();
        perform(post("/api/projects"), owner, body("key", key, "name", "Project " + key))
                .andExpect(status().isCreated());
        for (Account member : members) {
            perform(post("/api/projects/" + key + "/members"), owner, body("login", member.username()))
                    .andExpect(status().isOk());
        }
        return key;
    }

    protected JsonNode task(Account account, String projectKey, String title, Object... extra) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("projectKey", projectKey);
        fields.put("title", title);
        fields.put("description", "");
        fields.put("priority", "MEDIUM");
        for (int i = 0; i < extra.length; i += 2) {
            fields.put((String) extra[i], extra[i + 1]);
        }
        return read(perform(post("/api/tasks"), account, json.writeValueAsString(fields))
                .andExpect(status().isCreated()));
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request, Account account) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + account.token()));
    }

    protected ResultActions perform(MockHttpServletRequestBuilder request, Account account, String content)
            throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + account.token())
                .contentType(MediaType.APPLICATION_JSON).content(content));
    }

    protected JsonNode getJson(String url, Account account) throws Exception {
        return read(perform(get(url), account).andExpect(status().isOk()));
    }

    protected List<Long> ids(JsonNode array) {
        List<Long> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    protected List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.get(field).asText()));
        return values;
    }

    protected JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected String body(String... pairs) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return json.writeValueAsString(map);
    }

    protected static String uniqueName(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8);
    }
}
