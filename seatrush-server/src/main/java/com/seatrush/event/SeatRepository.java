package com.seatrush.event;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByEventIdOrderByRowNoAscSeatNoAsc(Long eventId);

    List<Seat> findByHoldIdOrderByIdAsc(Long holdId);

    /** SELECT ... FOR UPDATE: the rows stay locked until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :ids order by s.id")
    List<Seat> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);
}