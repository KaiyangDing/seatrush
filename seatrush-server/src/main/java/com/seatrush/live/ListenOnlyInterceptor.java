package com.seatrush.live;

import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * Browsers may only listen to seat topics. Without this check any client could SEND a made-up seat
 * update to /topic/... and the broker would pass it on to everybody watching that event.
 */
class ListenOnlyInterceptor implements ChannelInterceptor {

    private static final Pattern SEAT_TOPIC = Pattern.compile("/topic/events/\\d+/seats");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor stomp = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (stomp == null || stomp.getCommand() == null) {
            return message; // heartbeat
        }
        switch (stomp.getCommand()) {
            case CONNECT, STOMP, DISCONNECT, UNSUBSCRIBE -> {
            }
            case SUBSCRIBE -> {
                String destination = stomp.getDestination();
                if (destination == null || !SEAT_TOPIC.matcher(destination).matches()) {
                    throw new MessageDeliveryException(message, "Only seat topics can be subscribed to");
                }
            }
            default -> throw new MessageDeliveryException(message, "Clients can only listen, not " + stomp.getCommand());
        }
        return message;
    }
}
