package com.seatrush.event;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** jwt() puts a ready-made token on the request, so these tests do not depend on the login flow. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventFlowTest {

    private static final String THREE_BY_FOUR = """
            {"name": "Test Concert", "venue": "Hall A", "startsAt": "2030-01-01T19:30:00Z",
             "seatRows": 3, "seatsPerRow": 4}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void adminCreatesAnEventAndItsSeats() throws Exception {
        String body = createEventAsAdmin(THREE_BY_FOUR)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalSeats").value(12))
                .andReturn().getResponse().getContentAsString();
        Number eventId = JsonPath.read(body, "$.id");

        mockMvc.perform(get("/api/events/{id}/seats", eventId.longValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(12))
                .andExpect(jsonPath("$.seats[0].row").value(1))
                .andExpect(jsonPath("$.seats[0].seat").value(1))
                .andExpect(jsonPath("$.seats[11].row").value(3))
                .andExpect(jsonPath("$.seats[11].seat").value(4))
                .andExpect(jsonPath("$.seats[*].status", everyItem(is("AVAILABLE"))));
    }

    @Test
    void customerCannotCreateEvents() throws Exception {
        mockMvc.perform(post("/api/admin/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THREE_BY_FOUR))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousUserCannotCreateEvents() throws Exception {
        mockMvc.perform(post("/api/admin/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THREE_BY_FOUR))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidEventIsRejected() throws Exception {
        createEventAsAdmin(THREE_BY_FOUR.replace("\"seatRows\": 3", "\"seatRows\": 0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownEventReturns404() throws Exception {
        mockMvc.perform(get("/api/events/{id}", 999_999))
                .andExpect(status().isNotFound());
    }

    private ResultActions createEventAsAdmin(String json) throws Exception {
        return mockMvc.perform(post("/api/admin/events")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }
}