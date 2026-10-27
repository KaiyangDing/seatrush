package com.seatrush.lock;

import java.util.Collection;

/** Other requests kept the seats busy for too long, so this one gave up. Trying again may work. */
public class SeatsBusyException extends RuntimeException {

    public SeatsBusyException(Collection<Long> seatIds) {
        super("Seats " + seatIds + " are busy with other requests, please try again");
    }
}
