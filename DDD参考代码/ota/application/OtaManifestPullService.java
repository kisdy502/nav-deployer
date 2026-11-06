package com.zioneer.robotmanager.robot.ota.application;

import com.zioneer.robotmanager.robot.domain.model.RobotManifestReport;
import com.zioneer.robotmanager.robot.ota.domain.config.OtaManifestPullProperties;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaManifestGatewayRepository;
import com.zioneer.robotmanager.robot.service.RobotManifestReportService;
import com.zioneer.robotmanager.robot.util.Parallel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

/**
 * 平台按需向机器人拉取当前版本清单：拉到的统一落库并触发清单变更收口，拉不到返回空、由调用方降级。
 */
@Service
public class OtaManifestPullService {

    private static final Logger log = LoggerFactory.getLogger(OtaManifestPullService.class);

    private final OtaManifestGatewayRepository otaManifestGatewayRepository;
    private final RobotManifestReportService robotManifestReportService;
    private final OtaManifestPullProperties properties;

    public OtaManifestPullService(
            OtaManifestGatewayRepository otaManifestGatewayRepository,
            RobotManifestReportService robotManifestReportService,
            OtaManifestPullProperties properties
    ) {
        this.otaManifestGatewayRepository = otaManifestGatewayRepository;
        this.robotManifestReportService = robotManifestReportService;
        this.properties = properties;
    }

    public Map<String, RobotManifestReport> refreshCurrentManifests(Collection<String> robotCodes) {
        return Parallel.map(robotCodes, properties.getTimeout(), this::refreshCurrentManifest);
    }

    private RobotManifestReport refreshCurrentManifest(String robotCode) {
        RobotManifestReport report = otaManifestGatewayRepository.findCurrentManifest(robotCode).orElse(null);
        if (report != null) {
            overwriteCurrentManifest(report);
        }
        return report;
    }

    private void overwriteCurrentManifest(RobotManifestReport report) {
        try {
            robotManifestReportService.overwriteCurrentManifest(report);
        } catch (RuntimeException exception) {
            log.info("pulled manifest apply skipped, robot_code={}, reason={}",
                    report.robotCode(), exception.getMessage());
        }
    }
}
