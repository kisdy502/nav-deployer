package com.zioneer.robotmanager.robot.ota.domain.otarelease.storage;

import com.zioneer.robotmanager.common.core.error.BusinessException;
import com.zioneer.robotmanager.robot.error.RobotManagementErrorCode;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaStorageProperties;
import com.zioneer.robotmanager.robot.ota.domain.otapackage.OtaPackageSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按配置的存储平台分派下载地址生成；没有对应实现时返回「暂不支持」。 */
@Component
public class OtaSourceAccessResolver {

    private final OtaStorageProperties properties;
    private final Map<OtaStoragePlatform, OtaSourceAccessStrategy> strategies;

    public OtaSourceAccessResolver(OtaStorageProperties properties, List<OtaSourceAccessStrategy> strategies) {
        this.properties = properties;
        this.strategies = strategies.stream()
                .collect(Collectors.toUnmodifiableMap(OtaSourceAccessStrategy::platform, Function.identity()));
    }

    public OtaSourceAccess resolve(OtaPackageSource source) {
        OtaSourceAccessStrategy strategy = strategies.get(properties.getPlatform());
        if (strategy == null) {
            throw new BusinessException(RobotManagementErrorCode.OTA_STORAGE_PLATFORM_UNSUPPORTED);
        }
        return strategy.resolve(source);
    }
}
