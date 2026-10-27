package com.seatrush.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class ListenOnlyInterceptorTest {

    private final ListenOnlyInterceptor interceptor = new ListenOnlyInterceptor();

    @Test
    void connectingAndSubscribingToASeatTopicIsAllowed() {
        Message<byte[]> connect = frame(StompCommand.CONNECT, null);
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, "/topic/events/42/seats");

        assertThat(interceptor.preSend(connect, null)).isSameAs(connect);
        assertThat(interceptor.preSend(subscribe, null)).isSameAs(subscribe);
    }

    @Test
    void sendingIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, "/topic/events/42/seats"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void subscribingToAnyOtherTopicIsRejected() {
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/events/42/holds"), null))
                .isInstanceOf(MessageDeliveryException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "/topic/**"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor headers = StompHeaderAccessor.create(command);
        if (destination != null) {
            headers.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE) {
            headers.setSubscriptionId("sub-0");
        }
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
