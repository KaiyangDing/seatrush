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
    private final Duration holdDuration;

    public HoldService(HoldRepository holdRepository, SeatRepository seatRepository,
                       EventRepository eventRepository, TransactionTemplate transactionTemplate,
                       ApplicationEventPublisher events, HoldProperties properties) {
        this.holdRepository = holdRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
        this.events = events;
        this.holdDuration = properties.duration();
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
        try {
            return transactionTemplate.execute(status -> holdSeats(userId, eventId, request));
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

        seatRepository.findByHoldIdOrderByIdAsc(holdId).forEach(Seat::release);
        hold.cancel();
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
                    seatRepository.findByHoldIdOrderByIdAsc(holdId).forEach(Seat::release);
                    hold.expire();
                });
    }

    /** Runs inside one transaction: the hold row and the seat updates commit or roll back together. */
    private HoldResponse holdSeats(long userId, long eventId, CreateHoldRequest request) {
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        SortedSet<Long> seatIds = new TreeSet<>(request.seatIds());
        if (seatIds.size() != request.seatIds().size()) {
            throw new InvalidHoldRequestException("Seat ids must not repeat");
        }

        // 1. Insert the hold first. A concurrent request with the same key now waits on the unique index.
        //    The column keeps milliseconds, so truncate: the first response then matches every later read.
        Instant expiresAt = Instant.now().plus(holdDuration).truncatedTo(ChronoUnit.MILLIS);
        Hold hold = holdRepository.saveAndFlush(new Hold(userId, eventId, request.idempotencyKey(), expiresAt));

        // 2. SELECT ... FOR UPDATE, in ascending id order, so two overlapping orders cannot deadlock
        List<Seat> seats = seatRepository.findAllByIdForUpdate(seatIds);
        if (seats.size() != seatIds.size() || seats.stream().anyMatch(s -> !s.getEventId().equals(eventId))) {
            throw new InvalidHoldRequestException("All seats must exist and belong to event " + eventId);
        }

        // 3. Check under the lock: no other transaction can change these rows until this one ends
        List<Long> unavailable = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getId)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatsUnavailableException(unavailable);
        }

        seats.forEach(seat -> seat.hold(hold.getId()));
        // 4. Starts the expiry countdown, but only once this transaction has committed
        events.publishEvent(new HoldCreatedEvent(hold.getId(), hold.getExpiresAt()));
        return new HoldResponse(hold.getId(), eventId, hold.getStatus(), List.copyOf(seatIds), hold.getExpiresAt());
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
