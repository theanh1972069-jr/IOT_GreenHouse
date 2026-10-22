package com.iot.greenhouse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceLiveStatus {

    private String deviceId;
    private String deviceName;
    private String zone;
    private boolean online;
    private ZonedDateTime lastSeen;

    private Double temperature;
    private Double humidity;
    private Integer soilMoisture;

    @Builder.Default
    private boolean autoMode = true;

    @Builder.Default
    private Double tempThreshold = 35.0;

    @Builder.Default
    private Double soilThreshold = 30.0;

    @Builder.Default
    private Double lightTempThreshold = 20.0;

    @Builder.Default
    private double totalPowerW = 0.0;

    private ZonedDateTime valveOverrideUntil;
    private ZonedDateTime fanOverrideUntil;
    private ZonedDateTime lightOverrideUntil;
    private Long valveOverrideSeconds;
    private Long fanOverrideSeconds;
    private Long lightOverrideSeconds;
    private String lastAlertMessage;

    @Builder.Default
    private List<ActuatorLiveView> actuators = new ArrayList<>();

    public record ActuatorLiveView(
            String id,
            String name,
            String pin,
            String type,
            boolean state,
            double powerW,
            double ratedPowerW
    ) {}

    public void recalculateTotalPower() {
        if (!this.online || actuators == null || actuators.isEmpty()) {
            this.totalPowerW = 0.0;
            return;
        }
        double sum = 0.0;
        for (ActuatorLiveView actuator : actuators) {
            if (actuator.state() && actuator.powerW() > 0) {
                sum += actuator.powerW();
            }
        }
        this.totalPowerW = Math.round(sum * 10.0) / 10.0;
    }
}
