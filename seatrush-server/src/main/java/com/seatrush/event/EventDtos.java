package com.seatrush.event;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response bodies for /api/events and /api/admin/events. */
public final class EventDtos {

    private EventDtos() {
    }

    public record CreateEventRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String venue,
            @NotNull @Future Instant startsAt,
            @Min(1) @Max(50) int seatRows,
            @Min(1) @Max(50) int seatsPerRow) {
    }

    public record EventResponse(Long id, String name, String venue, Instant startsAt,
                                int seatRows, int seatsPerRow, int totalSeats) {

        public static EventResponse from(Event event) {
            return new EventResponse(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(),
                    event.getSeatRows(), event.getSeatsPerRow(), event.getSeatRows() * event.getSeatsPerRow());
        }
    }

    public record SeatView(Long id, int row, int seat, SeatStatus status) {

        public static SeatView from(Seat seat) {
            return new SeatView(seat.getId(), seat.getRowNo(), seat.getSeatNo(), seat.getStatus());
        }
    }

    public record SeatMapResponse(Long eventId, int seatRows, int seatsPerRow, List<SeatView> seats) {
    }
}