package com.zioneer.robotmanager.robot.ota.domain.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Setter
@Getter
@Component("otaUpgradeTaskProperties")
@ConfigurationProperties(prefix = "ota.upgrade-task")
public class OtaUpgradeTaskProperties {

    private Duration timeout = Duration.ofDays(3);

}
