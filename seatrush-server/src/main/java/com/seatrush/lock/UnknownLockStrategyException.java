package com.seatrush.lock;

import java.util.List;

public class UnknownLockStrategyException extends RuntimeException {

    private final List<String> available;

    public UnknownLockStrategyException(String name, List<String> available) {
        super("Unknown seat lock strategy '" + name + "', expected one of " + available);
        this.available = List.copyOf(available);
    }

    public List<String> getAvailable() {
        return available;
    }
}
