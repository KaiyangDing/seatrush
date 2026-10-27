package com.seatrush.hold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.seatrush.event.EventDtos.CreateEventRequest;
import com.seatrush.event.EventService;
import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.hold.HoldDtos.HoldResponse;
import com.seatrush.lock.InMemorySeatLockStrategy;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The precheck turns down requests for seats that are already taken before they queue for a lock.
 * Not @Transactional: the requests run on several threads and have to commit for real.
 */
@SpringBootTest
class HoldPrecheckTest {

    @Autowired
    private HoldService holdService;

    @Autowired
    private SeatLockStrategies lockStrategies;

    @Autowired
    private LockProperties lockProperties;

    @Autowired
    private InMemorySeatLockStrategy inMemory;

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

    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private long eventId;
    private List<Long> seatIds;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Precheck Test", "Hall C", Instant.parse("2030-01-01T19:30:00Z"), 1, 4)).id();
        seatIds = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(Seat::getId)
                .toList();
        alice = userRepository.save(new User("pre_alice", "unused-hash", Role.CUSTOMER)).getId();
        bob = userRepository.save(new User("pre_bob", "unused-hash", Role.CUSTOMER)).getId();
    }

    @AfterEach
    void cleanUp() {
        release.countDown();
        pool.shutdownNow();
        lockStrategies.use(lockProperties.strategy());
        jdbcTemplate.update("DELETE FROM seats WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM holds WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'pre\\_%'");
    }

    @Test
    void takenSeatIsTurnedDownWithoutWaitingForTheLock() throws Exception {
        lockStrategies.use("in-memory");
        long seat = seatIds.get(0);
        holdService.createHold(alice, eventId, new CreateHoldRequest(List.of(seat), "first"));
        // Another request sits on the seat's lock and does not let go
        CountDownLatch locked = new CountDownLatch(1);
        pool.submit(() -> inMemory.runTransaction(seats(seat), () -> {
            locked.countDown();
            awaitQuietly(release);
            return null;
        }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> holdService.createHold(bob, eventId, new CreateHoldRequest(List.of(seat), "late")))
                .isInstanceOf(SeatsUnavailableException.class);

        // Without the precheck Bob would wait the whole lock timeout (5 s) and then be told the seats are busy
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void sameRequestSentManyTimesAtOnceGivesOneHold() throws Exception {
        CreateHoldRequest request = new CreateHoldRequest(List.of(seatIds.get(1), seatIds.get(2)), "double-click");
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HoldResponse>> answers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            answers.add(pool.submit(() -> {
                go.await();
                return holdService.createHold(alice, eventId, request);
            }));
        }
        go.countDown();

        // Copies that come late see the seats taken, but taken by this very request: they get the hold too
        List<Long> holdIds = new ArrayList<>();
        for (Future<HoldResponse> answer : answers) {
            holdIds.add(answer.get(30, TimeUnit.SECONDS).id());
        }
        assertThat(holdIds).containsOnly(holdIds.getFirst());
        assertThat(holdRepository.countByEventId(eventId)).isEqualTo(1);
    }

    private static SortedSet<Long> seats(long... ids) {
        SortedSet<Long> seats = new TreeSet<>();
        for (long id : ids) {
            seats.add(id);
        }
        return seats;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
