package com.seatrush.hold;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import com.seatrush.event.EventNotFoundException;
import com.seatrush.event.EventRepository;
import com.seatrush.event.Seat;
import com.seatrush.event.SeatRepository;
import com.seatrush.event.SeatStatus;
import com.seatrush.hold.HoldDtos.CreateHoldRequest;
import com.seatrush.hold.HoldDtos.HoldResponse;
import com.seatrush.lock.SeatLockStrategies;
import com.seatrush.live.SeatsChangedEvent;
import com.seatrush.lock.SeatLockStrategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;
    private final SeatLockStrategies lockStrategies;
    private final Duration holdDuration;
    private final boolean precheck;

    public HoldService(HoldRepository holdRepository, SeatRepository seatRepository,
                       EventRepository eventRepository, TransactionTemplate transactionTemplate,
                       ApplicationEventPublisher events, SeatLockStrategies lockStrategies,
                       HoldProperties properties) {
        this.holdRepository = holdRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
        this.lockStrategies = lockStrategies;
        this.holdDuration = properties.duration();
        this.precheck = properties.precheck();
    }

    /**
     * Not @Transactional on purpose: if a concurrent duplicate wins, its transaction has to be
     * fully rolled back before the original hold can be read back in a fresh one.
     */
    public HoldResponse createHold(long userId, long eventId, CreateHoldRequest request) {
        // A retry with the same key gets the original hold back instead of a second hold
        Optional<Hold> existing = holdRepository.findByUserIdAndIdempotencyKey(userId, request.idempotencyKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        // Checked before any lock is taken: a bad request should not make anyone wait
        SortedSet<Long> seatIds = new TreeSet<>(request.seatIds());
        if (seatIds.size() != request.seatIds().size()) {
            throw new InvalidHoldRequestException("Seat ids must not repeat");
        }
        if (precheck) {
            // The fast path for a rush: a seat that is already taken cannot be won by queueing for a lock.
            // This read takes no lock and may be a moment old, but a seat it sees taken really was taken;
            // seats it sees free are checked again under the strategy's lock.
            List<Long> taken = seatRepository.findTakenSeatIds(eventId, seatIds, SeatStatus.AVAILABLE);
            if (!taken.isEmpty()) {
                // Taken perhaps by a copy of this very request that committed a moment ago: then it is ours
                return holdRepository.findByUserIdAndIdempotencyKey(userId, request.idempotencyKey())
                        .map(this::toResponse)
                        .orElseThrow(() -> new SeatsUnavailableException(taken));
            }
        }
        // Read once: if an admin switches strategy meanwhile, this request still uses one strategy throughout
        SeatLockStrategy strategy = lockStrategies.current();
        try {
            // The strategy decides how this transaction is kept apart from others that want the same seats
            return strategy.runTransaction(seatIds, () -> transactionTemplate.execute(
                    status -> holdSeats(strategy, userId, eventId, request.idempotencyKey(), seatIds)));
        } catch (DataIntegrityViolationException e) {
            // Two requests with the same key ran at once; the unique key let only one of them commit
            return holdRepository.findByUserIdAndIdempotencyKey(userId, request.idempotencyKey())
                    .map(this::toResponse)
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(long userId, long holdId) {
        return holdRepository.findById(holdId)
                .filter(hold -> hold.getUserId() == userId)
                .map(this::toResponse)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
    }

    /** Simulated payment: the held seats become SOLD. Confirming twice returns the same result. */
    @Transactional
    public HoldResponse confirmHold(long userId, long holdId) {
        Hold hold = lockOwnHold(userId, holdId);
        if (hold.getStatus() == HoldStatus.CONFIRMED) {
            return toResponse(hold);
        }
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException("Hold " + holdId + " is " + hold.getStatus());
        }
        Instant now = Instant.now();
        if (hold.isExpiredAt(now)) {
            // The deadline counts even if nothing has released the seats yet
            throw new HoldNotActiveException("Hold " + holdId + " expired at " + hold.getExpiresAt());
        }

        List<Seat> seats = seatRepository.findByHoldIdOrderByIdAsc(holdId);
        seats.forEach(Seat::sell);
        hold.confirm(now);
        publishSeatChange(hold, seats, SeatStatus.SOLD);
        return toResponse(hold, seats);
    }

    /** Gives the seats back. Cancelling twice returns the same result. */
    @Transactional
    public HoldResponse cancelHold(long userId, long holdId) {
        Hold hold = lockOwnHold(userId, holdId);
        if (hold.getStatus() == HoldStatus.CANCELLED) {
            return toResponse(hold);
        }
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException("Hold " + holdId + " is " + hold.getStatus());
        }

        List<Seat> seats = seatRepository.findByHoldIdOrderByIdAsc(holdId);
        seats.forEach(Seat::release);
        hold.cancel();
        publishSeatChange(hold, seats, SeatStatus.AVAILABLE);
        // The seats are on sale again, so the hold no longer owns any
        return toResponse(hold, List.of());
    }

    /**
     * Called by the expiry thread once the deadline has passed. Takes the same hold lock as confirm
     * and cancel, so whichever comes first wins; a hold that was confirmed or cancelled in time is left alone.
     */
    @Transactional
    public void expireHold(long holdId) {
        holdRepository.findByIdForUpdate(holdId)
                .filter(hold -> hold.getStatus() == HoldStatus.ACTIVE)
                .ifPresent(hold -> {
                    List<Seat> seats = seatRepository.findByHoldIdOrderByIdAsc(holdId);
                    seats.forEach(Seat::release);
                    hold.expire();
                    publishSeatChange(hold, seats, SeatStatus.AVAILABLE);
                });
    }

    /** Runs inside one transaction: the hold row and the seat updates commit or roll back together. */
    private HoldResponse holdSeats(SeatLockStrategy strategy, long userId, long eventId, String idempotencyKey,
                                   SortedSet<Long> seatIds) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // 1. Insert the hold first. A concurrent request with the same key now waits on the unique index.
        //    The column keeps milliseconds, so truncate: the first response then matches every later read.
        Instant expiresAt = Instant.now().plus(holdDuration).truncatedTo(ChronoUnit.MILLIS);
        Hold hold = holdRepository.saveAndFlush(new Hold(userId, eventId, idempotencyKey, expiresAt));

        // 2. Read the seats the way the strategy says, e.g. SELECT ... FOR UPDATE for the pessimistic one
        List<Seat> seats = strategy.loadSeats(seatIds);
        if (seats.size() != seatIds.size() || seats.stream().anyMatch(s -> !s.getEventId().equals(eventId))) {
            throw new InvalidHoldRequestException("All seats must exist and belong to event " + eventId);
        }

        // 3. Check: the strategy guarantees no other request can hold these seats until this transaction ends
        List<Long> unavailable = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getId)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatsUnavailableException(unavailable);
        }

        seats.forEach(seat -> seat.hold(hold.getId()));
        // 4. Both only take effect once this transaction has committed: the expiry countdown starts,
        //    and every open seat map of the event is told that these seats are taken
        events.publishEvent(new HoldCreatedEvent(hold.getId(), hold.getExpiresAt()));
        publishSeatChange(hold, seats, SeatStatus.HELD);
        return new HoldResponse(hold.getId(), eventId, hold.getStatus(), List.copyOf(seatIds), hold.getExpiresAt());
    }

    /** Pushed to the browsers by SeatUpdatePublisher, but only after this transaction has committed. */
    private void publishSeatChange(Hold hold, List<Seat> seats, SeatStatus status) {
        List<Long> seatIds = seats.stream().map(Seat::getId).toList();
        events.publishEvent(new SeatsChangedEvent(hold.getEventId(), seatIds, status));
    }

    /**
     * Locks the hold row (SELECT ... FOR UPDATE) before anything touches its seats, so a confirm and a
     * cancel of the same hold run one after the other. A hold of another user looks exactly like a
     * missing one, so hold ids cannot be probed.
     */
    private Hold lockOwnHold(long userId, long holdId) {
        return holdRepository.findByIdForUpdate(holdId)
                .filter(hold -> hold.getUserId() == userId)
                .orElseThrow(() -> new HoldNotFoundException(holdId));
    }

    private HoldResponse toResponse(Hold hold) {
        return toResponse(hold, seatRepository.findByHoldIdOrderByIdAsc(hold.getId()));
    }

    private HoldResponse toResponse(Hold hold, List<Seat> seats) {
        List<Long> seatIds = seats.stream().map(Seat::getId).toList();
        return new HoldResponse(hold.getId(), hold.getEventId(), hold.getStatus(), seatIds, hold.getExpiresAt());
    }
}
