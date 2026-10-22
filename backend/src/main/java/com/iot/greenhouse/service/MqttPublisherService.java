package com.iot.greenhouse.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MqttPublisherService {

    private final MessageChannel mqttOutboundChannel;

    public MqttPublisherService(@Autowired(required = false)
                                @Qualifier("mqttOutboundChannel") MessageChannel mqttOutboundChannel) {
        this.mqttOutboundChannel = mqttOutboundChannel;
    }

    /**
     * Publishes a message to an MQTT topic. Returns true if delivered to the outbound channel,
     * or false if MQTT is disabled or an error occurred.
     */
    public boolean publish(String topic, String payload) {
        if (mqttOutboundChannel == null) {
            log.warn("MQTT outbound is disabled. Cannot send payload to [{}]: {}", topic, payload);
            return false;
        }
        try {
            Message<String> message = MessageBuilder.withPayload(payload)
                    .setHeader(MqttHeaders.TOPIC, topic)
                    .build();
            boolean sent = mqttOutboundChannel.send(message, 3000);
            if (sent) {
                log.info("Successfully published MQTT message to [{}]: {}", topic, payload);
            } else {
                log.warn("Timeout sending MQTT message to [{}]", topic);
            }
            return sent;
        } catch (Exception e) {
            log.error("Failed to publish MQTT message to topic [{}]: {}", topic, e.getMessage(), e);
            return false;
        }
    }
}
