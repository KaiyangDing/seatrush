package com.seatrush.loadtest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The load-test accounts lt_user_0001, lt_user_0002, ... They are created once and reused by later runs. */
final class Users {

    static final String PASSWORD = "load-test-password";

    /** BCrypt makes every login cost the server real CPU time, so the setup runs a few at a time. */
    private static final int PARALLEL_LOGINS = 16;

    private Users() {
    }

    /** Returns username to access token, in order. */
    static Map<String, String> logIn(SeatRushClient client, int count) throws InterruptedException, ExecutionException {
        Map<String, Future<String>> pending = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_LOGINS)) {
            for (int i = 1; i <= count; i++) {
                String username = "lt_user_%04d".formatted(i);
                pending.put(username, pool.submit(() -> {
                    client.register(username, PASSWORD);
                    return client.login(username, PASSWORD);
                }));
            }
            Map<String, String> tokens = new LinkedHashMap<>();
            for (Map.Entry<String, Future<String>> entry : pending.entrySet()) {
                tokens.put(entry.getKey(), entry.getValue().get());
            }
            return tokens;
        }
    }
}
