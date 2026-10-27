package com.seatrush.live;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Pushes every committed seat change to the topic of its event, where the seat map pages listen. */
@Component
public class SeatUpdatePublisher {

    private final SimpMessagingTemplate messaging;

    public SeatUpdatePublisher(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public static String topic(long eventId) {
        return "/topic/events/" + eventId + "/seats";
    }

    /** Runs after the commit; if the transaction rolls back, the event is simply dropped. */
    @TransactionalEventListener
    void onSeatsChanged(SeatsChangedEvent event) {
        messaging.convertAndSend(topic(event.eventId()), event);
    }
}
