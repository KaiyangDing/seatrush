package com.seatrush.loadtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The few SeatRush API calls the load test needs, on top of java.net.http.HttpClient. One instance
 * is shared by every thread: HttpClient is thread-safe and keeps a pool of connections.
 */
public class SeatRushClient {

    /** One HTTP answer, with the time from sending the request to having the whole body. */
    public record Reply(int status, JsonNode body, long nanos) {

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public String detail() {
            return body.path("detail").asString("");
        }
    }

    private final URI baseUri;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public SeatRushClient(URI baseUri) {
        this.baseUri = baseUri;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public URI baseUri() {
        return baseUri;
    }

    public Reply send(String method, String path, String token, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.method(method, BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(request.build(), BodyHandlers.ofString());
        long nanos = System.nanoTime() - start;
        JsonNode parsed = response.body().isBlank() ? json.missingNode() : readJson(response.body());
        return new Reply(response.statusCode(), parsed, nanos);
    }

    // ---------- setup calls: anything unexpected stops the load test ----------

    /** Creates the account, or does nothing if it already exists from an earlier run. */
    public void register(String username, String password) throws IOException, InterruptedException {
        Reply reply = send("POST", "/api/auth/register", null, Map.of("username", username, "password", password));
        if (!reply.isSuccess() && reply.status() != 409) {
            throw failure("register " + username, reply);
        }
    }

    public String login(String username, String password) throws IOException, InterruptedException {
        Reply reply = expect(send("POST", "/api/auth/login", null, Map.of("username", username, "password", password)),
                "log in as " + username);
        return reply.body().path("accessToken").asString();
    }

    public long createEvent(String adminToken, String name, int rows, int seatsPerRow) throws IOException, InterruptedException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("venue", "Load test hall");
        event.put("startsAt", Instant.now().plus(Duration.ofDays(30)).toString());
        event.put("seatRows", rows);
        event.put("seatsPerRow", seatsPerRow);
        return expect(send("POST", "/api/admin/events", adminToken, event), "create event").body().path("id").asLong();
    }

    /** Seat ids row by row, seat by seat, with their current status. */
    public Map<Long, String> seatMap(long eventId) throws IOException, InterruptedException {
        Reply reply = expect(send("GET", "/api/events/" + eventId + "/seats", null, null), "read seat map");
        Map<Long, String> seats = new LinkedHashMap<>();
        for (JsonNode seat : reply.body().path("seats")) {
            seats.put(seat.path("id").asLong(), seat.path("status").asString());
        }
        return seats;
    }

    public String currentStrategy(String adminToken) throws IOException, InterruptedException {
        return expect(send("GET", "/api/admin/lock-strategy", adminToken, null), "read lock strategy")
                .body().path("current").asString();
    }

    public List<String> availableStrategies(String adminToken) throws IOException, InterruptedException {
        List<String> names = new ArrayList<>();
        for (JsonNode name : expect(send("GET", "/api/admin/lock-strategy", adminToken, null), "read lock strategy")
                .body().path("available")) {
            names.add(name.asString());
        }
        return names;
    }

    public void useStrategy(String adminToken, String name) throws IOException, InterruptedException {
        expect(send("PUT", "/api/admin/lock-strategy", adminToken, Map.of("strategy", name)), "switch to " + name);
    }

    // ---------- the calls under load: the caller looks at the reply itself ----------

    public Reply hold(String token, long eventId, List<Long> seatIds, String idempotencyKey)
            throws IOException, InterruptedException {
        return send("POST", "/api/events/" + eventId + "/holds", token,
                Map.of("seatIds", seatIds, "idempotencyKey", idempotencyKey));
    }

    public Reply cancel(String token, long holdId) throws IOException, InterruptedException {
        return send("POST", "/api/holds/" + holdId + "/cancel", token, null);
    }

    private JsonNode readJson(String text) {
        try {
            return json.readTree(text);
        } catch (RuntimeException e) {
            return json.missingNode(); // an error page instead of JSON
        }
    }

    private static Reply expect(Reply reply, String what) {
        if (!reply.isSuccess()) {
            throw failure(what, reply);
        }
        return reply;
    }

    private static IllegalStateException failure(String what, Reply reply) {
        return new IllegalStateException("Could not " + what + ": HTTP " + reply.status() + " " + reply.body());
    }
}
