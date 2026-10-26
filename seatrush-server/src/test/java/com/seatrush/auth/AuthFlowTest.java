package com.seatrush.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** Runs against the local MySQL; each test rolls back, so no test users are left behind. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerLoginAndCallAProtectedEndpoint() throws Exception {
        register("flow_alice", "password123")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("flow_alice"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        String body = login("flow_alice", "password123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.accessToken");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("flow_alice"))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        register("flow_bob", "password123").andExpect(status().isCreated());

        register("flow_bob", "another-password").andExpect(status().isConflict());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        register("flow_carol", "password123").andExpect(status().isCreated());

        login("flow_carol", "not-the-password").andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRegistrationIsRejected() throws Exception {
        register("x", "short").andExpect(status().isBadRequest());
    }

    @Test
    void protectedEndpointNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    private ResultActions register(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(username, password)));
    }

    private static String credentials(String username, String password) {
        return """
                {"username": "%s", "password": "%s"}
                """.formatted(username, password);
    }
}