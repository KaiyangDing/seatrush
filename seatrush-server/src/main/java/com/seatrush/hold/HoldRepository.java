package com.seatrush.hold;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldRepository extends JpaRepository<Hold, Long> {

    Optional<Hold> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    long countByEventId(Long eventId);

    List<Hold> findByStatus(HoldStatus status);

    /** SELECT ... FOR UPDATE on one hold: confirm, cancel and expiry of the same hold run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hold h where h.id = :id")
    Optional<Hold> findByIdForUpdate(@Param("id") Long id);
}
