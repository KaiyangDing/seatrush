package com.seatrush.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.seatrush.event.EventDtos.CreateEventRequest;
import com.seatrush.event.EventService;
import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import com.seatrush.event.SeatStatus;
import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The real background thread with holds that last two seconds. Not @Transactional: the expiry
 * thread only sees committed rows, so the data is cleaned up by hand after each test.
 */
@SpringBootTest(properties = "seatrush.holds.duration=2s")
class HoldExpiryTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldExpiryScheduler scheduler;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private EventService eventService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long eventId;
    private List<Long> seatIds;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Expiry Test", "Hall E", Instant.parse("2030-01-01T19:30:00Z"), 1, 4)).id();
        seatIds = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(Seat::getId)
                .toList();
        alice = userRepository.save(new User("exp_alice", "unused-hash", Role.CUSTOMER)).getId();
        bob = userRepository.save(new User("exp_bob", "unused-hash", Role.CUSTOMER)).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM seats WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM holds WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'exp\\_%'");
    }

    @Test
    void seatsGoBackOnSaleWhenTheDeadlinePasses() {
        List<Long> seats = List.of(seatIds.get(0), seatIds.get(1));
        long holdId = holdService.createHold(alice, eventId, new CreateHoldRequest(seats, "slow-payer")).id();
        assertThat(statusOf(holdId)).isEqualTo(HoldStatus.ACTIVE);

        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(holdId) == HoldStatus.EXPIRED);

        assertThat(seatRepository.findAllById(seats)).allSatisfy(seat -> {
            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.getHoldId()).isNull();
        });
        // Someone else can buy them now
        holdService.createHold(bob, eventId, new CreateHoldRequest(seats, "next-in-line"));
    }

    @Test
    void confirmedAndCancelledHoldsAreLeftAlone() {
        long confirmed = holdService.createHold(alice, eventId,
                new CreateHoldRequest(List.of(seatIds.get(0)), "paid")).id();
        long cancelled = holdService.createHold(alice, eventId,
                new CreateHoldRequest(List.of(seatIds.get(1)), "changed-mind")).id();
        holdService.confirmHold(alice, confirmed);
        holdService.cancelHold(alice, cancelled);

        // What the background thread does when the deadline arrives
        holdService.expireHold(confirmed);
        holdService.expireHold(cancelled);

        assertThat(statusOf(confirmed)).isEqualTo(HoldStatus.CONFIRMED);
        assertThat(statusOf(cancelled)).isEqualTo(HoldStatus.CANCELLED);
        assertThat(seatStatusOf(seatIds.get(0))).isEqualTo(SeatStatus.SOLD);
        assertThat(seatStatusOf(seatIds.get(1))).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void restartPicksUpHoldsThatWereStillActive() {
        // While the expiry thread is down, two ACTIVE holds end up in the database but in no queue:
        // one already past its deadline and one with an hour to go
        scheduler.stop();
        long overdue = insertUnscheduledHold("crash-overdue", Instant.now().minus(Duration.ofMinutes(1)), seatIds.get(2));
        long running = insertUnscheduledHold("crash-running", Instant.now().plus(Duration.ofHours(1)), seatIds.get(3));

        scheduler.start();

        await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(overdue) == HoldStatus.EXPIRED);
        assertThat(seatStatusOf(seatIds.get(2))).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(statusOf(running)).isEqualTo(HoldStatus.ACTIVE);
        assertThat(seatStatusOf(seatIds.get(3))).isEqualTo(SeatStatus.HELD);
    }

    /** Saving through the repository skips HoldService, so nothing schedules the hold. */
    private long insertUnscheduledHold(String key, Instant expiresAt, long seatId) {
        long holdId = holdRepository.save(new Hold(alice, eventId, key, expiresAt)).getId();
        jdbcTemplate.update("UPDATE seats SET status = 'HELD', hold_id = ? WHERE id = ?", holdId, seatId);
        return holdId;
    }

    private HoldStatus statusOf(long holdId) {
        return holdRepository.findById(holdId).orElseThrow().getStatus();
    }

    private SeatStatus seatStatusOf(long seatId) {
        return seatRepository.findById(seatId).orElseThrow().getStatus();
    }
}
