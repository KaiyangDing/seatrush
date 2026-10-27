package com.seatrush.loadtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.seatrush.loadtest.Outcome.Attempt;
import com.seatrush.loadtest.Outcome.Kind;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rush against a stand-in server from the JDK. The server holds every request back until all of
 * them have arrived: if the client sent them one after another, the first would time out.
 */
class RushTest {

    private static final int USERS = 50;

    private final CountDownLatch allArrived = new CountDownLatch(USERS);
    private final AtomicBoolean seatTaken = new AtomicBoolean();
    private HttpServer server;
    private SeatRushClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), USERS);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/events/7/holds", this::hold);
        server.start();
        client = new SeatRushClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void everyRequestIsInFlightAtOnceAndExactlyOneWins() throws Exception {
        List<Attempt> attempts = new ArrayList<>();
        for (int i = 0; i < USERS; i++) {
            attempts.add(new Attempt("user" + i, "token" + i, List.of(1L)));
        }

        RushResult result = Rush.run(client, 7, attempts);

        assertThat(result.count(Kind.ERROR)).isZero();
        assertThat(result.count(Kind.HELD)).isEqualTo(1);
        assertThat(result.count(Kind.TAKEN)).isEqualTo(USERS - 1);
        assertThat(result.outcomes()).allSatisfy(outcome -> assertThat(outcome.nanos()).isPositive());
    }

    private void hold(HttpExchange exchange) throws IOException {
        allArrived.countDown();
        boolean together;
        try {
            together = allArrived.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            together = false;
        }
        if (!together) {
            reply(exchange, 500, "{\"detail\": \"the requests did not arrive together\"}");
        } else if (seatTaken.compareAndSet(false, true)) {
            reply(exchange, 201, "{\"id\": 99, \"seatIds\": [1]}");
        } else {
            reply(exchange, 409, "{\"detail\": \"Seats [1] are no longer available\", \"unavailableSeatIds\": [1]}");
        }
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
