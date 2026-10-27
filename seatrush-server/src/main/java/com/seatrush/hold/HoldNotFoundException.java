package com.seatrush.hold;

public class HoldNotFoundException extends RuntimeException {

    public HoldNotFoundException(long holdId) {
        super("Hold " + holdId + " does not exist");
    }
}
