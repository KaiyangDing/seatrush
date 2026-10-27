package com.seatrush.loadtest;

import java.util.List;

import com.seatrush.loadtest.SeatRushClient.Reply;

/** What happened to one hold request of a rush. */
public record Outcome(Attempt attempt, Kind kind, int status, long nanos, long holdId, String detail) {

    public enum Kind {
        /** 201: the seats belong to this user now. */
        HELD,
        /** 409 naming the seats: somebody else was faster, the expected answer for most users. */
        TAKEN,
        /** 409 without seats: the request gave up waiting for a lock, or kept losing version conflicts. */
        BUSY,
        /** Anything else, which should never happen: a 5xx, a timeout, a dropped connection. */
        ERROR
    }

    /** One user's request: who sends it and which seats they want. */
    public record Attempt(String user, String token, List<Long> seatIds) {
    }

    static Outcome of(Attempt attempt, Reply reply) {
        if (reply.status() == 201) {
            return new Outcome(attempt, Kind.HELD, 201, reply.nanos(), reply.body().path("id").asLong(), "");
        }
        if (reply.status() == 409) {
            Kind kind = reply.body().has("unavailableSeatIds") ? Kind.TAKEN : Kind.BUSY;
            return new Outcome(attempt, kind, 409, reply.nanos(), 0, reply.detail());
        }
        return new Outcome(attempt, Kind.ERROR, reply.status(), reply.nanos(), 0, reply.detail());
    }

    static Outcome failed(Attempt attempt, long nanos, Exception e) {
        return new Outcome(attempt, Kind.ERROR, 0, nanos, 0, e.toString());
    }
}
