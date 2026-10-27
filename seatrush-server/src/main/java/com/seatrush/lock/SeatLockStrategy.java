package com.seatrush.lock;

import java.util.List;
import java.util.SortedSet;
import java.util.function.Supplier;

import com.seatrush.event.Seat;

/**
 * One way of making sure that two requests can never hold the same seat.
 * <p>
 * HoldService does the actual work in one transaction: insert the hold, check that the seats are
 * AVAILABLE, mark them HELD. A strategy only decides how that transaction is kept apart from other
 * transactions that want the same seats. It gets two places to do so:
 * <ul>
 *   <li>{@link #runTransaction} wraps the whole transaction, commit included. A lock that lives
 *       outside the database (a Java lock, a Redis key) is taken and released here.</li>
 *   <li>{@link #loadSeats} runs inside the transaction and reads the seats. A database lock
 *       (SELECT ... FOR UPDATE) is taken here.</li>
 * </ul>
 */
public interface SeatLockStrategy {

    /** Short name for logs, the admin page and the load-test report. */
    String name();

    /**
     * Runs {@code transaction}, which opens, does the work and commits a fresh transaction each time
     * it is called. The default just runs it once.
     *
     * @param seatIds the seats the transaction is about to check and change, in ascending order
     */
    default <T> T runTransaction(SortedSet<Long> seatIds, Supplier<T> transaction) {
        return transaction.get();
    }

    /**
     * Called inside the transaction. Returns the requested seats that exist, in ascending id order,
     * read so that checking them and then marking them HELD is safe until the transaction ends.
     */
    List<Seat> loadSeats(SortedSet<Long> seatIds);
}
