package com.seatrush.event;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "seat_rows", nullable = false)
    private int seatRows;

    @Column(name = "seats_per_row", nullable = false)
    private int seatsPerRow;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
    }

    public Event(String name, String venue, Instant startsAt, int seatRows, int seatsPerRow) {
        this.name = name;
        this.venue = venue;
        this.startsAt = startsAt;
        this.seatRows = seatRows;
        this.seatsPerRow = seatsPerRow;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public int getSeatRows() {
        return seatRows;
    }

    public int getSeatsPerRow() {
        return seatsPerRow;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}