package com.seatrush.live;

import java.util.List;

import com.seatrush.event.SeatStatus;

/**
 * Some seats of an event now have a new status. Published inside the transaction that changed them,
 * and only pushed to the browsers once that transaction has committed, so nobody ever sees a change
 * that was rolled back. The same record is the JSON body of the WebSocket message.
 */
public record SeatsChangedEvent(long eventId, List<Long> seatIds, SeatStatus status) {
}
