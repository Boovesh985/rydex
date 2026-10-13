package com.rydex.common.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class WsDestinationsTest {

    @Test
    void rideLocationTopicRoundTrips() {
        UUID rideId = UUID.randomUUID();

        String topic = WsDestinations.rideLocation(rideId);

        assertThat(topic).isEqualTo("/topic/rides/" + rideId + "/location");
        assertThat(WsDestinations.rideIdOfLocationTopic(topic)).isEqualTo(rideId);
    }

    @Test
    void anythingElseIsNotARideLocationTopic() {
        assertThat(WsDestinations.rideIdOfLocationTopic(null)).isNull();
        assertThat(WsDestinations.rideIdOfLocationTopic("/topic/rides/not-a-uuid/location")).isNull();
        assertThat(WsDestinations.rideIdOfLocationTopic("/topic/rides/" + UUID.randomUUID() + "/status")).isNull();
        assertThat(WsDestinations.rideIdOfLocationTopic("/topic/rides//location")).isNull();
        assertThat(WsDestinations.rideIdOfLocationTopic("/queue/offers")).isNull();
    }

    @Test
    void pushesNeedADestinationAndAPayload() {
        UUID user = UUID.randomUUID();

        WsPush push = WsPush.toUser(user, WsDestinations.OFFERS, "hello");

        assertThat(push.user()).isEqualTo(user.toString());
        assertThat(WsPush.toTopic("/topic/x", 1).user()).isNull();
        assertThatThrownBy(() -> new WsPush(null, null, "x"))
                .isInstanceOf(NullPointerException.class);
    }
}
