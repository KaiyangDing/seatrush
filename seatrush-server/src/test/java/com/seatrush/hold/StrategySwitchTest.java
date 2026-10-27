package com.seatrush.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.seatrush.event.EventDtos.CreateEventRequest;
import com.seatrush.event.EventService;
import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import com.seatrush.event.SeatStatus;
import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.hold.HoldDtos.HoldResponse;
import com.seatrush.lock.LockProperties;
import com.seatrush.lock.SeatLockStrategies;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What happens to a hold that is still running when an admin switches the lock strategy:
 * <ol>
 *   <li>Alice's request runs under the pessimistic strategy. It has locked the seat row with
 *       SELECT ... FOR UPDATE and is paused just before its commit.</li>
 *   <li>The strategy is switched to in-memory, and Bob asks for the same seat. His request takes no
 *       row lock, so Alice's lock does not stop his read: he sees the seat still AVAILABLE and
 *       marks it HELD as well.</li>
 *   <li>Alice commits. Bob's UPDATE ... WHERE version = 0 now matches no row.</li>
 * </ol>
 * The @Version column on Seat is the last line of defence: Bob gets a 409 and the seat stays Alice's.
 * Not @Transactional: both requests have to commit for real.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StrategySwitchTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HoldService holdService;

    @Autowired
    private SeatLockStrategies lockStrategies;

    @Autowired
    private LockProperties lockProperties;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private EventService eventService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final CountDownLatch alicePaused = new CountDownLatch(1);
    private final CountDownLatch aliceMayCommit = new CountDownLatch(1);
    private final AtomicInteger holdsSeen = new AtomicInteger();

    /** HoldService publishes HoldCreatedEvent inside the hold's transaction, so this runs just before each commit. */
    private final ApplicationListener<ApplicationEvent> beforeCommit = event -> {
        if (event instanceof PayloadApplicationEvent<?> payload && payload.getPayload() instanceof HoldCreatedEvent) {
            if (holdsSeen.incrementAndGet() == 1) {
                // Alice: wait here, still holding the row lock
                alicePaused.countDown();
                awaitQuietly(aliceMayCommit);
            } else {
                // Bob has read the seat as AVAILABLE and marked it HELD: now Alice may commit
                aliceMayCommit.countDown();
            }
        }
    };

    private long eventId;
    private long seatId;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Switch Test", "Hall S", Instant.parse("2030-01-01T19:30:00Z"), 1, 1)).id();
        seatId = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).getFirst().getId();
        alice = userRepository.save(new User("switch_alice", "unused-hash", Role.CUSTOMER)).getId();
        bob = userRepository.save(new User("switch_bob", "unused-hash", Role.CUSTOMER)).getId();
        lockStrategies.use("pessimistic");
        context.addApplicationListener(beforeCommit);
    }

    @AfterEach
    void cleanUp() {
        context.removeApplicationListener(beforeCommit);
        aliceMayCommit.countDown();
        lockStrategies.use(lockProperties.strategy());
        jdbcTemplate.update("DELETE FROM seats WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM holds WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'switch\\_%'");
    }

    @Test
    void holdStartedBeforeASwitchIsStillProtectedByTheVersionColumn() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<HoldResponse> alicesHold = pool.submit(() ->
                holdService.createHold(alice, eventId, new CreateHoldRequest(List.of(seatId), "before-switch")));
        assertThat(alicePaused.await(10, TimeUnit.SECONDS)).isTrue();

        lockStrategies.use("in-memory");

        mockMvc.perform(post("/api/events/{eventId}/holds", eventId)
                        .with(jwt().jwt(token -> token.subject(String.valueOf(bob))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\": [" + seatId + "], \"idempotencyKey\": \"after-switch\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("changed by another request")));

        HoldResponse alicesResponse = alicesHold.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        Seat seat = seatRepository.findById(seatId).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getHoldId()).isEqualTo(alicesResponse.id());
        assertThat(holdRepository.countByEventId(eventId)).isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
