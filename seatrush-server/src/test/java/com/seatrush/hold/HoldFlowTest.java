package com.seatrush.hold;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import com.seatrush.event.EventDtos.CreateEventRequest;
import com.seatrush.event.EventService;
import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** HTTP-level checks of the hold endpoint; everything rolls back after each test. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HoldFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventService eventService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private UserRepository userRepository;

    private long eventId;
    private List<Long> seatIds;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Hold Test", "Hall A", Instant.parse("2030-01-01T19:30:00Z"), 2, 5)).id();
        seatIds = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(Seat::getId)
                .toList();
        alice = userRepository.save(new User("hold_alice", "unused-hash", Role.CUSTOMER)).getId();
        bob = userRepository.save(new User("hold_bob", "unused-hash", Role.CUSTOMER)).getId();
    }

    @Test
    void holdingSeatsMarksThemHeld() throws Exception {
        hold(alice, List.of(seatIds.get(0), seatIds.get(1)), "key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatIds.length()").value(2));

        mockMvc.perform(get("/api/events/{id}/seats", eventId))
                .andExpect(jsonPath("$.seats[0].status").value("HELD"))
                .andExpect(jsonPath("$.seats[1].status").value("HELD"))
                .andExpect(jsonPath("$.seats[2].status").value("AVAILABLE"));
    }

    @Test
    void retryWithTheSameKeyReturnsTheSameHold() throws Exception {
        String first = hold(alice, List.of(seatIds.get(0)), "retry-key")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number firstId = JsonPath.read(first, "$.id");

        hold(alice, List.of(seatIds.get(0)), "retry-key")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(firstId.longValue()));
    }

    @Test
    void seatHeldBySomeoneElseIsRejected() throws Exception {
        hold(alice, List.of(seatIds.get(0)), "alice-key").andExpect(status().isCreated());

        hold(bob, List.of(seatIds.get(0), seatIds.get(1)), "bob-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.unavailableSeatIds[0]").value(seatIds.get(0)));
    }

    @Test
    void atMostFourSeatsPerHold() throws Exception {
        hold(alice, seatIds.subList(0, 5), "too-many").andExpect(status().isBadRequest());
    }

    @Test
    void seatsMustBelongToTheEvent() throws Exception {
        long otherEvent = eventService.createEvent(new CreateEventRequest(
                "Other", "Hall B", Instant.parse("2030-02-01T19:30:00Z"), 1, 1)).id();

        mockMvc.perform(post("/api/events/{id}/holds", otherEvent)
                        .with(jwt().jwt(token -> token.subject(String.valueOf(alice))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(seatIds.get(0)), "wrong-event")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousUserCannotHoldSeats() throws Exception {
        mockMvc.perform(post("/api/events/{id}/holds", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(seatIds.get(0)), "anon")))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions hold(long userId, List<Long> seats, String key) throws Exception {
        return mockMvc.perform(post("/api/events/{id}/holds", eventId)
                .with(jwt().jwt(token -> token.subject(String.valueOf(userId))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(seats, key)));
    }

    private static String body(List<Long> seats, String key) {
        return """
                {"seatIds": %s, "idempotencyKey": "%s"}
                """.formatted(seats, key);
    }
}