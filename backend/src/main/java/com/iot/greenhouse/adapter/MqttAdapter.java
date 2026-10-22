package com.iot.greenhouse.adapter;

import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.service.DeviceLiveService;
import com.iot.greenhouse.service.TelemetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/**
 * MQTT transport for device telemetry and control. Subscribes to {@code greenhouse/{deviceId}/telemetry}
 * and provides outbound publishing channel for device actuation.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = false)
public class MqttAdapter {

    private static final String TOPIC = "greenhouse/+/telemetry";

    private static final String CLIENT_ID = "greenhouse-telemetry-subscriber";
    private static final String PUBLISHER_CLIENT_ID = "greenhouse-control-publisher";

    private final TelemetryService telemetryService;
    private final TelemetryPayloadParser payloadParser;
    private final MqttPahoClientFactory mqttClientFactory;
    private final DeviceLiveService deviceLiveService;

    public MqttAdapter(TelemetryService telemetryService,
                       TelemetryPayloadParser payloadParser,
                       MqttPahoClientFactory mqttClientFactory,
                       @Lazy DeviceLiveService deviceLiveService) {
        this.telemetryService = telemetryService;
        this.payloadParser = payloadParser;
        this.mqttClientFactory = mqttClientFactory;
        this.deviceLiveService = deviceLiveService;
    }

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageProducer inbound(MessageChannel mqttInputChannel) {
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(CLIENT_ID, mqttClientFactory, TOPIC);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(1);
        adapter.setOutputChannel(mqttInputChannel);
        return adapter;
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttInputChannel")
    public MessageHandler handler() {
        return this::onTelemetryMessage;
    }

    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler outboundHandler() {
        MqttPahoMessageHandler messageHandler =
                new MqttPahoMessageHandler(PUBLISHER_CLIENT_ID, mqttClientFactory);
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(1);
        return messageHandler;
    }

    void onTelemetryMessage(Message<?> message) {
        String topic = String.valueOf(message.getHeaders().get("mqtt_receivedTopic"));
        String payload = String.valueOf(message.getPayload());
        log.info("Received MQTT message on topic: {}", topic);

        String[] parts = topic.split("/");
        if (parts.length < 3 || parts[1].isBlank()) {
            log.warn("Ignoring telemetry on unexpected topic: {}", topic);
            return;
        }
        String deviceId = parts[1];

        Telemetry telemetry = payloadParser.parse(payload, deviceId);
        if (telemetry != null) {
            telemetryService.processTelemetryData(deviceId, telemetry);
        }
        if (deviceLiveService != null) {
            deviceLiveService.updateFromTelemetry(deviceId, payload, telemetry);
        }
    }
}
