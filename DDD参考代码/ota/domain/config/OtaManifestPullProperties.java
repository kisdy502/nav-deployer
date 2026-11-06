package com.zioneer.robotmanager.robot.ota.domain.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Setter
@Getter
@Component("otaManifestPullProperties")
@ConfigurationProperties(prefix = "ota.manifest-pull")
public class OtaManifestPullProperties {

    /** 单台机器人清单拉取的等待上限；真离线在网关在线检查处毫秒级返回，吃满超时的只有不应答的假在线。 */
    private Duration timeout = Duration.ofSeconds(5);

}
