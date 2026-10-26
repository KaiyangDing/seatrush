package com.seatrush.event;

import java.util.ArrayList;
import java.util.List;

import com.seatrush.event.EventDtos.CreateEventRequest;
import com.seatrush.event.EventDtos.EventResponse;
import com.seatrush.event.EventDtos.SeatMapResponse;
import com.seatrush.event.EventDtos.SeatView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private static final String INSERT_SEAT = "INSERT INTO seats (event_id, row_no, seat_no) VALUES (?, ?, ?)";

    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final JdbcTemplate jdbcTemplate;

    public EventService(EventRepository eventRepository, SeatRepository seatRepository,
                        JdbcTemplate jdbcTemplate) {
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Creates the event and all of its seats in one transaction: either both exist or neither does. */
    @Transactional
    public EventResponse createEvent(CreateEventRequest request) {
        Event event = eventRepository.saveAndFlush(new Event(request.name(), request.venue(),
                request.startsAt(), request.seatRows(), request.seatsPerRow()));

        // Seats use IDENTITY ids, which stop Hibernate from batching inserts, so a JDBC batch is used instead
        List<Object[]> seats = new ArrayList<>(request.seatRows() * request.seatsPerRow());
        for (int row = 1; row <= request.seatRows(); row++) {
            for (int seat = 1; seat <= request.seatsPerRow(); seat++) {
                seats.add(new Object[] {event.getId(), row, seat});
            }
        }
        jdbcTemplate.batchUpdate(INSERT_SEAT, seats);
        return EventResponse.from(event);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> listEvents() {
        return eventRepository.findAllByOrderByStartsAtAsc().stream()
                .map(EventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(long eventId) {
        return EventResponse.from(findEvent(eventId));
    }

    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(long eventId) {
        Event event = findEvent(eventId);
        List<SeatView> seats = seatRepository.findByEventIdOrderByRowNoAscSeatNoAsc(eventId).stream()
                .map(SeatView::from)
                .toList();
        return new SeatMapResponse(event.getId(), event.getSeatRows(), event.getSeatsPerRow(), seats);
    }

    private Event findEvent(long eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }
}