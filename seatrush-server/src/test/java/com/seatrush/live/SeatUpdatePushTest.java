package com.seatrush.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.seatrush.event.EventDtos.CreateEventRequest;
import com.seatrush.event.EventService;
import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import com.seatrush.event.SeatStatus;
import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.hold.HoldService;
import com.seatrush.user.Role;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * A real STOMP client on a real port listens to one event's topic, the way the seat map page does.
 * Not @Transactional: messages are only sent after a commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeatUpdatePushTest {

    @LocalServerPort
    private int port;

    @Autowired
    private HoldService holdService;

    @Autowired
    private EventService eventService;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SimpMessagingTemplate messaging;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final BlockingQueue<SeatsChangedEvent> received = new LinkedBlockingQueue<>();
    private WebSocketStompClient client;
    private StompSession listener;
    private long eventId;
    private List<Long> seatIds;
    private long alice;
    private long bob;

    @BeforeEach
    void setUp() throws Exception {
        eventId = eventService.createEvent(new CreateEventRequest(
                "Push Test", "Hall P", Instant.parse("2030-01-01T19:30:00Z"), 1, 4)).id();
        seatIds = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(Seat::getId)
                .toList();
        alice = userRepository.save(new User("push_alice", "unused-hash", Role.CUSTOMER)).getId();
        bob = userRepository.save(new User("push_bob", "unused-hash", Role.CUSTOMER)).getId();

        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        listener = connect();
        listener.subscribe(SeatUpdatePublisher.topic(eventId), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return SeatsChangedEvent.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((SeatsChangedEvent) payload);
            }
        });
        awaitSubscription();
    }

    @AfterEach
    void cleanUp() {
        listener.disconnect();
        client.stop();
        jdbcTemplate.update("DELETE FROM seats WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM holds WHERE event_id = ?", eventId);
        jdbcTemplate.update("DELETE FROM events WHERE id = ?", eventId);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'push\\_%'");
    }

    @Test
    void everyCommittedChangeIsPushed() throws Exception {
        long sold = holdService.createHold(alice, eventId,
                new CreateHoldRequest(List.of(seatIds.get(0), seatIds.get(1)), "buy")).id();
        assertThat(nextChange()).isEqualTo(change(SeatStatus.HELD, seatIds.get(0), seatIds.get(1)));

        holdService.confirmHold(alice, sold);
        assertThat(nextChange()).isEqualTo(change(SeatStatus.SOLD, seatIds.get(0), seatIds.get(1)));

        long cancelled = holdService.createHold(bob, eventId, new CreateHoldRequest(List.of(seatIds.get(2)), "maybe")).id();
        assertThat(nextChange()).isEqualTo(change(SeatStatus.HELD, seatIds.get(2)));

        holdService.cancelHold(bob, cancelled);
        assertThat(nextChange()).isEqualTo(change(SeatStatus.AVAILABLE, seatIds.get(2)));
    }

    @Test
    void expiryIsPushed() throws Exception {
        long holdId = holdService.createHold(alice, eventId, new CreateHoldRequest(List.of(seatIds.get(3)), "slow")).id();
        assertThat(nextChange().status()).isEqualTo(SeatStatus.HELD);

        // What the expiry thread does when the deadline has passed
        holdService.expireHold(holdId);

        assertThat(nextChange()).isEqualTo(change(SeatStatus.AVAILABLE, seatIds.get(3)));
    }

    @Test
    void changeThatIsRolledBackIsNeverPushed() throws Exception {
        // The hold marks the seat HELD and publishes the change, then its transaction rolls back. Here an
        // outer transaction forces that; in real life it is e.g. a version conflict at commit.
        transactionTemplate.executeWithoutResult(status -> {
            holdService.createHold(alice, eventId, new CreateHoldRequest(List.of(seatIds.get(0)), "rolled-back"));
            status.setRollbackOnly();
        });

        assertThat(nextChangeWithin(Duration.ofMillis(500))).isNull();
        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void clientsCannotPushFakeUpdates() throws Exception {
        StompSession attacker = connect();
        attacker.send(SeatUpdatePublisher.topic(eventId), change(SeatStatus.SOLD, seatIds.get(0)));

        assertThat(nextChangeWithin(Duration.ofMillis(500))).isNull();
    }

    private StompSession connect() throws Exception {
        return client.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
        }).get(5, TimeUnit.SECONDS);
    }

    /**
     * SUBSCRIBE is asynchronous, so the test sends empty pings to the topic until one comes back.
     * Real changes always name at least one seat, so pings are easy to skip later.
     */
    private void awaitSubscription() {
        SeatsChangedEvent ping = new SeatsChangedEvent(eventId, List.of(), SeatStatus.AVAILABLE);
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            messaging.convertAndSend(SeatUpdatePublisher.topic(eventId), ping);
            return received.poll(100, TimeUnit.MILLISECONDS) != null;
        });
    }

    private SeatsChangedEvent nextChange() throws InterruptedException {
        SeatsChangedEvent next = nextChangeWithin(Duration.ofSeconds(5));
        assertThat(next).as("a pushed seat change").isNotNull();
        return next;
    }

    private SeatsChangedEvent nextChangeWithin(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            SeatsChangedEvent next = received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (next == null || !next.seatIds().isEmpty()) {
                return next;
            }
        }
    }

    private SeatsChangedEvent change(SeatStatus status, Long... seats) {
        return new SeatsChangedEvent(eventId, List.of(seats), status);
    }
}
