package com.seatrush.event;

import java.util.List;

import com.seatrush.event.EventDtos.EventResponse;
import com.seatrush.event.EventDtos.SeatMapResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public List<EventResponse> listEvents() {
        return eventService.listEvents();
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable long eventId) {
        return eventService.getEvent(eventId);
    }

    @GetMapping("/{eventId}/seats")
    public SeatMapResponse getSeatMap(@PathVariable long eventId) {
        return eventService.getSeatMap(eventId);
    }
}