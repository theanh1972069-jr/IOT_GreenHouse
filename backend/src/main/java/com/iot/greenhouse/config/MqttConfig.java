package com.iot.greenhouse.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;

@Configuration
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = false)
public class MqttConfig {

    @Value("${mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.username:}")
    private String username;

    @Value("${mqtt.password:}")
    private String password;

    @Value("${mqtt.clean-session:false}")
    private boolean cleanSession;

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] { brokerUrl });

        // Paho's MqttConnectOptions has no setClientId; the id travels with the client
        // instance the adapter constructs, so the factory only holds shared options.
        options.setCleanSession(cleanSession);

        // Without these the adapter gives up after one failed attempt and stays dead
        // until the application is restarted.
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(60);
        options.setKeepAliveInterval(30);

        if (username != null && !username.isBlank()) {
            options.setUserName(username);
            options.setPassword(password == null ? new char[0] : password.toCharArray());
        }

        factory.setConnectionOptions(options);
        return factory;
    }
}
