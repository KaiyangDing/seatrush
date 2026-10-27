package com.seatrush.loadtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.seatrush.loadtest.Outcome.Attempt;

/**
 * What the users of one rush want. Every scenario has its own hall, and the users pick their seats
 * from one seeded Random, so the same seed gives every strategy exactly the same requests.
 */
public enum Scenario {

    /** Everybody wants the same seat: one winner and the most contention there can be. */
    HOT_SEAT("hot-seat", "everybody wants the same seat", 1, 10) {
        @Override
        List<Long> pick(List<Long> seatIds, Random random) {
            return List.of(seatIds.getFirst());
        }
    },

    /** Pairs of neighbours in one short row: many overlapping requests for few seats. */
    HOT_ROW("hot-row", "pairs of neighbours in one row of 20 seats", 1, 20) {
        @Override
        List<Long> pick(List<Long> seatIds, Random random) {
            return neighbours(seatIds, random);
        }
    },

    /** Pairs of neighbours anywhere in a big hall: requests rarely collide. */
    SPREAD("spread", "pairs of neighbours anywhere in a 50 x 50 hall", 50, 50) {
        @Override
        List<Long> pick(List<Long> seatIds, Random random) {
            return neighbours(seatIds, random);
        }
    };

    private final String id;
    private final String description;
    private final int rows;
    private final int seatsPerRow;

    Scenario(String id, String description, int rows, int seatsPerRow) {
        this.id = id;
        this.description = description;
        this.rows = rows;
        this.seatsPerRow = seatsPerRow;
    }

    /**
     * One attempt per user, in the order of the tokens. The same seed always gives the same users the
     * same seats, so every strategy faces exactly the same requests.
     *
     * @param seatIds every seat of the hall, row by row, as the seat map lists them
     */
    public List<Attempt> attempts(List<Long> seatIds, Map<String, String> tokens, long seed) {
        Random random = new Random(seed);
        List<Attempt> attempts = new ArrayList<>();
        tokens.forEach((user, token) -> attempts.add(new Attempt(user, token, pick(seatIds, random))));
        return attempts;
    }

    abstract List<Long> pick(List<Long> seatIds, Random random);

    /** Two seats next to each other in the same row, never wrapping round to the next row. */
    List<Long> neighbours(List<Long> seatIds, Random random) {
        int row = random.nextInt(rows);
        int seat = random.nextInt(seatsPerRow - 1);
        int first = row * seatsPerRow + seat;
        return List.of(seatIds.get(first), seatIds.get(first + 1));
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public int rows() {
        return rows;
    }

    public int seatsPerRow() {
        return seatsPerRow;
    }

    public static Scenario fromId(String id) {
        for (Scenario scenario : values()) {
            if (scenario.id.equals(id)) {
                return scenario;
            }
        }
        throw new IllegalArgumentException("Unknown scenario '" + id + "', expected hot-seat, hot-row or spread");
    }
}
