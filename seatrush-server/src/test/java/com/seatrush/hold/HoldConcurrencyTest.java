package com.seatrush.hold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Real threads, real transactions, real row locks. Not @Transactional: every thread has to
 * commit on its own, so the data is cleaned up by hand after each test.
 */
@SpringBootTest
class HoldConcurrencyTest {

    private static final int USERS = 100;

    @Autowired
    private HoldService holdService;

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

    private long eventId;
    private List<Long> seatIds;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Race Test", "Hall R", Instant.parse("2030-01-01T19:30:00Z"), 1, 2)).id();
        seatIds = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(Seat::getId)
                .toList();
        List<User> users = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            users.add(new User("race_" + i, "unused-hash", Role.CUSTOMER));
        }
        userIds = userRepository.saveAll(users).stream().map(User::getId).toList();
    }

    @AfterEach
    void cleanUp() {
        // Children before parents because of the foreign keys
        jdbcTemplate.update("DELETE FROM seats WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM holds WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'race\\_%'");
    }

    @Test
    void hundredUsersRaceForOneSeatAndExactlyOneWins() throws Exception {
        long seatId = seatIds.get(0);
        List<Callable<?>> attempts = new ArrayList<>();
        for (long userId : userIds) {
            attempts.add(() -> holdService.createHold(userId, eventId,
                    new CreateHoldRequest(List.of(seatId), "race-" + userId)));
        }

        RaceResult result = race(attempts);

        assertThat(result.errors()).isEmpty();
        assertThat(result.successes()).isEqualTo(1);
        assertThat(result.conflicts()).isEqualTo(USERS - 1);
        assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(holdRepository.countByEventId(eventId)).isEqualTo(1);
    }

    @Test
    void overlappingRequestsInOppositeOrderDoNotDeadlock() throws Exception {
        List<Long> forward = List.of(seatIds.get(0), seatIds.get(1));
        List<Long> backward = List.of(seatIds.get(1), seatIds.get(0));
        List<Callable<?>> attempts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long userId = userIds.get(i);
            List<Long> seats = i % 2 == 0 ? forward : backward;
            attempts.add(() -> holdService.createHold(userId, eventId,
                    new CreateHoldRequest(seats, "pair-" + userId)));
        }

        RaceResult result = race(attempts);

        // A deadlock would show up as an error (MySQL aborts one of the transactions)
        assertThat(result.errors()).isEmpty();
        assertThat(result.successes()).isEqualTo(1);
        assertThat(result.conflicts()).isEqualTo(49);
    }

    @Test
    void confirmAndCancelRacingOnOneHoldNeverMixStates() throws Exception {
        long owner = userIds.get(0);
        long holdId = holdService.createHold(owner, eventId, new CreateHoldRequest(seatIds, "lifecycle")).id();
        List<Callable<?>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                attempts.add(() -> holdService.confirmHold(owner, holdId));
            } else {
                attempts.add(() -> holdService.cancelHold(owner, holdId));
            }
        }

        RaceResult result = race(attempts);

        // Whichever action locks the hold first wins. Later calls of the same action are harmless
        // repeats, and every call of the other action is rejected.
        assertThat(result.errors()).isEmpty();
        assertThat(result.successes()).isEqualTo(10);
        assertThat(result.conflicts()).isEqualTo(10);
        HoldStatus finalStatus = holdRepository.findById(holdId).orElseThrow().getStatus();
        assertThat(finalStatus).isIn(HoldStatus.CONFIRMED, HoldStatus.CANCELLED);
        SeatStatus expected = finalStatus == HoldStatus.CONFIRMED ? SeatStatus.SOLD : SeatStatus.AVAILABLE;
        assertThat(seatRepository.findAllById(seatIds)).extracting(Seat::getStatus).containsOnly(expected);
    }

    /** Starts every attempt on its own thread and releases them all at the same moment. */
    private RaceResult race(List<Callable<?>> attempts) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
        CountDownLatch startSignal = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (Callable<?> attempt : attempts) {
            pool.submit(() -> {
                startSignal.await();
                try {
                    attempt.call();
                    successes.incrementAndGet();
                } catch (SeatsUnavailableException | HoldNotActiveException e) {
                    conflicts.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            });
        }
        startSignal.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return new RaceResult(successes.get(), conflicts.get(), errors);
    }

    private record RaceResult(int successes, int conflicts, List<Throwable> errors) {
    }
}
