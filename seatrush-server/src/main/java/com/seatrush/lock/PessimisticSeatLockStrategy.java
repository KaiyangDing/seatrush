package com.seatrush.lock;

import java.util.List;
import java.util.SortedSet;

import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Lets MySQL do the locking: the seat rows are read with SELECT ... FOR UPDATE, so a second request
 * for the same seats waits at that read until the first transaction commits or rolls back, and then
 * sees the seats already HELD.
 * <p>
 * The rows are locked in ascending id order, so two requests for overlapping seats cannot deadlock.
 * Nothing extra is needed around the transaction, so {@link #runTransaction} keeps its default.
 */
@Component
@Order(1)
public class PessimisticSeatLockStrategy implements SeatLockStrategy {

    private final SeatRepository seatRepository;

    public PessimisticSeatLockStrategy(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public List<Seat> loadSeats(SortedSet<Long> seatIds) {
        return seatRepository.findAllByIdForUpdate(seatIds);
    }
}
