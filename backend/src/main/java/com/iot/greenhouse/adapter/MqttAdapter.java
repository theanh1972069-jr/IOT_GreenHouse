package com.iot.greenhouse.adapter;

import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.service.TelemetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/**
 * MQTT transport for device telemetry. Subscribes to {@code sh/home1/{deviceId}/telemetry}
 * and the legacy {@code greenhouse/{deviceId}/telemetry}
 * and hands the payload to {@link TelemetryPayloadParser}.
 *
 * <p>This class is a {@code @Configuration}, not a plain {@code @Component}, so that
 * {@link #mqttInputChannel()} is proxied and every {@code @Bean} method receives the
 * same channel instance. As a plain component the call inside {@link #inbound()} would
 * build a second {@link DirectChannel}, leaving the adapter publishing to a channel
 * nothing was subscribed to ("Dispatcher has no subscribers").
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = false)
public class MqttAdapter {

    private static final String[] TOPICS = {
            "sh/home1/+/telemetry",
            "greenhouse/+/telemetry"
    };

    /**
     * Stable across restarts so the broker can resume the persistent session and hand
     * over the messages it buffered while the backend was down. Must match
     * {@code mqtt.client-id}.
     */
    private static final String CLIENT_ID = "greenhouse-telemetry-subscriber";

    private final TelemetryService telemetryService;
    private final TelemetryPayloadParser payloadParser;
    private final MqttPahoClientFactory mqttClientFactory;

    public MqttAdapter(TelemetryService telemetryService,
                       TelemetryPayloadParser payloadParser,
                       MqttPahoClientFactory mqttClientFactory) {
        this.telemetryService = telemetryService;
        this.payloadParser = payloadParser;
        this.mqttClientFactory = mqttClientFactory;
    }

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageProducer inbound(MessageChannel mqttInputChannel) {
        // A stable client id is required for a persistent session (cleanSession=false):
        // the broker keys the queued session by id, so a random id per boot would
        // abandon everything it buffered while the backend was down.
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(CLIENT_ID, mqttClientFactory, TOPICS);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        // Injected rather than calling the method, so this is the channel the
        // @ServiceActivator handler below is actually subscribed to.
        adapter.setOutputChannel(mqttInputChannel);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {
        return this::onTelemetryMessage;
    }

    void onTelemetryMessage(Message<?> message) {
        String topic = String.valueOf(message.getHeaders().get("mqtt_receivedTopic"));
        String payload = String.valueOf(message.getPayload());
        log.info("Received MQTT message on topic: {}", topic);

        String[] parts = topic.split("/");
        String deviceId;
        if (parts.length == 4 && "sh".equals(parts[0]) && "home1".equals(parts[1])
                && "telemetry".equals(parts[3])) {
            deviceId = parts[2];
        } else if (parts.length == 3 && "greenhouse".equals(parts[0])
                && "telemetry".equals(parts[2])) {
            deviceId = parts[1];
        } else {
            log.warn("Ignoring telemetry on unexpected topic: {}", topic);
            return;
        }
        if (deviceId.isBlank()) {
            log.warn("Ignoring telemetry with an empty device id on topic: {}", topic);
            return;
        }

        Telemetry telemetry = payloadParser.parse(payload, deviceId);
        if (telemetry == null) {
            return;
        }
        telemetryService.processTelemetryData(deviceId, telemetry);
    }
}
