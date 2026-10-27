package com.seatrush.lock;

import java.util.List;
import java.util.SortedSet;
import java.util.function.Supplier;

import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Takes no lock at all. The seats are read normally, and a conflict is only noticed when the change
 * is written: Seat has a @Version column, so Hibernate writes
 * <pre>UPDATE seats SET status = 'HELD', ..., version = v + 1 WHERE id = ? AND version = v</pre>
 * If another transaction changed the seat after it was read, no row matches, Hibernate throws and
 * the whole transaction rolls back. It is then simply run again: the retry reads the seat afresh,
 * sees it HELD and answers 409 like every other strategy.
 * <p>
 * Nobody ever waits for a lock, which pays off when conflicts are rare. In a rush for one seat,
 * though, almost every request does all its work only to roll it back.
 */
@Component
@Order(2)
public class OptimisticSeatLockStrategy implements SeatLockStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticSeatLockStrategy.class);

    /** The first retry normally sees the winner's commit; more are only needed if the seats change again. */
    static final int MAX_ATTEMPTS = 3;

    private final SeatRepository seatRepository;

    public OptimisticSeatLockStrategy(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public <T> T runTransaction(SortedSet<Long> seatIds, Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                // Thrown at commit, after the transaction has already been rolled back
                if (attempt == MAX_ATTEMPTS) {
                    throw new SeatsBusyException(seatIds);
                }
                log.debug("Seats {} changed during attempt {}, retrying", seatIds, attempt);
            }
        }
    }

    @Override
    public List<Seat> loadSeats(SortedSet<Long> seatIds) {
        return seatRepository.findByIdInOrderByIdAsc(seatIds);
    }
}
