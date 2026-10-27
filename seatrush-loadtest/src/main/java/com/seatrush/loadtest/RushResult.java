package com.seatrush.loadtest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.seatrush.loadtest.Outcome.Kind;

/** Every outcome of one rush plus how long it took, from the moment the requests were let go. */
public record RushResult(List<Outcome> outcomes, long wallNanos) {

    public long count(Kind kind) {
        return outcomes.stream().filter(outcome -> outcome.kind() == kind).count();
    }

    /** Answered requests per second over the whole rush. */
    public double throughput() {
        return outcomes.size() / (wallNanos / 1e9);
    }

    public double wallMillis() {
        return wallNanos / 1e6;
    }

    /** Nearest-rank percentile of the response times, in milliseconds; percentile is in (0, 100]. */
    public double latencyMillis(double percentile) {
        long[] sorted = outcomes.stream().mapToLong(Outcome::nanos).sorted().toArray();
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100 * sorted.length);
        return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)] / 1e6;
    }

    /** Every seat that some successful request holds. */
    public Set<Long> heldSeats() {
        Set<Long> seats = new HashSet<>();
        outcomes.stream().filter(o -> o.kind() == Kind.HELD).forEach(o -> seats.addAll(o.attempt().seatIds()));
        return seats;
    }

    /**
     * Checks the one thing that must never go wrong: no seat may be held twice. Compares the successful
     * answers with each other and with the seat map the server shows afterwards. An empty list means
     * the rush was handled correctly.
     */
    public List<String> problems(Map<Long, String> seatsAfter) {
        List<String> problems = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Outcome outcome : outcomes) {
            if (outcome.kind() != Kind.HELD) {
                continue;
            }
            for (long seat : outcome.attempt().seatIds()) {
                if (!seen.add(seat)) {
                    problems.add("Seat " + seat + " was given to two requests");
                }
                if (!"HELD".equals(seatsAfter.get(seat))) {
                    problems.add("Seat " + seat + " was given out but the server shows it " + seatsAfter.get(seat));
                }
            }
        }
        long heldOnServer = seatsAfter.values().stream().filter("HELD"::equals).count();
        if (heldOnServer != seen.size()) {
            problems.add("The server shows " + heldOnServer + " held seats, the successful requests got " + seen.size());
        }
        return problems;
    }
}
