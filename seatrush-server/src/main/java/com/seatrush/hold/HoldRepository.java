package com.seatrush.hold;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HoldRepository extends JpaRepository<Hold, Long> {

    Optional<Hold> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    long countByEventId(Long eventId);
}