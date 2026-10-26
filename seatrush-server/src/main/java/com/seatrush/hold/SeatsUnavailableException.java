package com.seatrush.hold;

import java.util.List;

public class SeatsUnavailableException extends RuntimeException {

    private final List<Long> seatIds;

    public SeatsUnavailableException(List<Long> seatIds) {
        super("Seats " + seatIds + " are no longer available");
        this.seatIds = List.copyOf(seatIds);
    }

    public List<Long> getSeatIds() {
        return seatIds;
    }
}