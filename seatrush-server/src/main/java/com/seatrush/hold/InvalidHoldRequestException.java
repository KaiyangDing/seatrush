package com.seatrush.hold;

public class InvalidHoldRequestException extends RuntimeException {

    public InvalidHoldRequestException(String message) {
        super(message);
    }
}