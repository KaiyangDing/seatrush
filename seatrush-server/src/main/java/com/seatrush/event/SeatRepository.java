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

    /** A plain read without any lock, for the strategies that protect the seats some other way. */
    List<Seat> findByIdInOrderByIdAsc(Collection<Long> ids);

    /** Which of these seats of the event are taken right now; a plain read without any lock. */
    @Query("select s.id from Seat s where s.eventId = :eventId and s.id in :ids and s.status <> :available order by s.id")
    List<Long> findTakenSeatIds(@Param("eventId") long eventId, @Param("ids") Collection<Long> ids,
                                @Param("available") SeatStatus available);

    /** SELECT ... FOR UPDATE: the rows stay locked until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Seat s where s.id in :ids order by s.id")
    List<Seat> findAllByIdForUpdate(@Param("ids") Collection<Long> ids);
}
