package com.seatrush.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "seat_no", nullable = false)
    private int seatNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SeatStatus status;

    @Column(name = "hold_id")
    private Long holdId;

    // Used by the optimistic-locking strategy in phase 2
    @Version
    private long version;

    protected Seat() {
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public int getRowNo() {
        return rowNo;
    }

    public int getSeatNo() {
        return seatNo;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public Long getHoldId() {
        return holdId;
    }

    public long getVersion() {
        return version;
    }

    /** Only called while the row is locked; the service has already checked the status. */
    public void hold(Long holdId) {
        if (status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("Seat " + id + " is " + status + ", not AVAILABLE");
        }
        this.status = SeatStatus.HELD;
        this.holdId = holdId;
    }

    /** HELD -> SOLD. The seat keeps its hold_id as the record of who bought it. */
    public void sell() {
        if (status != SeatStatus.HELD) {
            throw new IllegalStateException("Seat " + id + " is " + status + ", not HELD");
        }
        this.status = SeatStatus.SOLD;
    }

    /** HELD -> AVAILABLE: the hold was cancelled or ran out, so the seat goes back on sale. */
    public void release() {
        if (status != SeatStatus.HELD) {
            throw new IllegalStateException("Seat " + id + " is " + status + ", not HELD");
        }
        this.status = SeatStatus.AVAILABLE;
        this.holdId = null;
    }
}
