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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

/** Confirm, cancel and read a hold over HTTP; everything rolls back after each test. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HoldLifecycleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventService eventService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private UserRepository userRepository;

    private long eventId;
    private List<Long> seatIds;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Lifecycle Test", "Hall L", Instant.parse("2030-01-01T19:30:00Z"), 2, 5)).id();
        seatIds = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(Seat::getId)
                .toList();
        alice = userRepository.save(new User("life_alice", "unused-hash", Role.CUSTOMER)).getId();
        bob = userRepository.save(new User("life_bob", "unused-hash", Role.CUSTOMER)).getId();
    }

    @Test
    void confirmSellsTheSeats() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0), seatIds.get(1)), "buy");

        act(alice, holdId, "confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatIds.length()").value(2));

        mockMvc.perform(get("/api/events/{id}/seats", eventId))
                .andExpect(jsonPath("$.seats[0].status").value("SOLD"))
                .andExpect(jsonPath("$.seats[1].status").value("SOLD"))
                .andExpect(jsonPath("$.seats[2].status").value("AVAILABLE"));
    }

    @Test
    void confirmingTwiceIsHarmless() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0)), "double-click");

        act(alice, holdId, "confirm").andExpect(status().isOk());
        act(alice, holdId, "confirm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.seatIds[0]").value(seatIds.get(0)));
    }

    @Test
    void cancelPutsTheSeatsBackOnSale() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0)), "change-of-mind");

        act(alice, holdId, "cancel")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.seatIds.length()").value(0));

        mockMvc.perform(get("/api/events/{id}/seats", eventId))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
        createHold(bob, List.of(seatIds.get(0)), "bob-gets-it");
    }

    @Test
    void cancelledHoldCannotBeConfirmed() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0)), "cancel-then-pay");
        act(alice, holdId, "cancel").andExpect(status().isOk());

        act(alice, holdId, "confirm").andExpect(status().isConflict());
    }

    @Test
    void confirmedHoldCannotBeCancelled() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0)), "pay-then-cancel");
        act(alice, holdId, "confirm").andExpect(status().isOk());

        act(alice, holdId, "cancel").andExpect(status().isConflict());
    }

    @Test
    void expiredHoldCannotBeConfirmed() throws Exception {
        // Saved directly with a deadline in the past: nothing has released it yet, but it is too late to pay
        long holdId = holdRepository.save(
                new Hold(alice, eventId, "too-late", Instant.now().minusSeconds(1))).getId();

        act(alice, holdId, "confirm").andExpect(status().isConflict());
    }

    @Test
    void ownerCanReadTheirHold() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0)), "read-me");

        mockMvc.perform(get("/api/holds/{id}", holdId).with(as(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.seatIds[0]").value(seatIds.get(0)));
    }

    @Test
    void holdOfAnotherUserLooksMissing() throws Exception {
        long holdId = createHold(alice, List.of(seatIds.get(0)), "not-yours");

        mockMvc.perform(get("/api/holds/{id}", holdId).with(as(bob))).andExpect(status().isNotFound());
        act(bob, holdId, "confirm").andExpect(status().isNotFound());
        act(bob, holdId, "cancel").andExpect(status().isNotFound());

        mockMvc.perform(get("/api/holds/{id}", holdId).with(as(alice)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private long createHold(long userId, List<Long> seats, String key) throws Exception {
        String response = mockMvc.perform(post("/api/events/{id}/holds", eventId)
                        .with(as(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seatIds": %s, "idempotencyKey": "%s"}
                                """.formatted(seats, key)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(response, "$.id").longValue();
    }

    private ResultActions act(long userId, long holdId, String action) throws Exception {
        return mockMvc.perform(post("/api/holds/{id}/{action}", holdId, action).with(as(userId)));
    }

    private static RequestPostProcessor as(long userId) {
        return jwt().jwt(token -> token.subject(String.valueOf(userId)));
    }
}
