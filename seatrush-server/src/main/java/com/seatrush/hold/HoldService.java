package com.seatrush.hold;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HoldService {

    private final HoldRepository holdRepository;
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration holdDuration;

    public HoldService(HoldRepository holdRepository, SeatRepository seatRepository,
                       EventRepository eventRepository, TransactionTemplate transactionTemplate,
                       HoldProperties properties) {
        this.holdRepository = holdRepository;
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = transactionTemplate;
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
        Hold hold = holdRepository.saveAndFlush(
                new Hold(userId, eventId, request.idempotencyKey(), Instant.now().plus(holdDuration)));

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
        return new HoldResponse(hold.getId(), eventId, hold.getStatus(), List.copyOf(seatIds), hold.getExpiresAt());
    }

    private HoldResponse toResponse(Hold hold) {
        List<Long> seatIds = seatRepository.findByHoldIdOrderByIdAsc(hold.getId()).stream()
                .map(Seat::getId)
                .toList();
        return new HoldResponse(hold.getId(), hold.getEventId(), hold.getStatus(), seatIds, hold.getExpiresAt());
    }
}