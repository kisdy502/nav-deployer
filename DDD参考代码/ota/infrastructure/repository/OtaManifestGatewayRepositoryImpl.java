package com.zioneer.robotmanager.robot.ota.infrastructure.repository;

import com.zioneer.robotmanager.common.core.result.Result;
import com.zioneer.robotmanager.connectivity.ota.OtaManifestFetchResult;
import com.zioneer.robotmanager.robot.action.infrastructure.repository.client.RobotAccessGatewayClient;
import com.zioneer.robotmanager.robot.domain.model.RobotManifestReport;
import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import com.zioneer.robotmanager.robot.ota.domain.repository.OtaManifestGatewayRepository;
import com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct.OtaManifestFetchMapstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
public class OtaManifestGatewayRepositoryImpl implements OtaManifestGatewayRepository {

    private final RobotAccessGatewayClient robotAccessGatewayClient;
    private final OtaManifestFetchMapstruct otaManifestFetchMapstruct;

    @Autowired
    public OtaManifestGatewayRepositoryImpl(
            RobotAccessGatewayClient robotAccessGatewayClient,
            OtaManifestFetchMapstruct otaManifestFetchMapstruct
    ) {
        this.robotAccessGatewayClient = robotAccessGatewayClient;
        this.otaManifestFetchMapstruct = otaManifestFetchMapstruct;
    }

    @Override
    public Optional<RobotManifestReport> findCurrentManifest(String robotCode) {
        try {
            Result<OtaManifestFetchResult> result = robotAccessGatewayClient.getOtaManifest(robotCode);
            if (result == null || result.getStatus() != Result.SUCCESS_STATUS || result.getData() == null) {
                log.info("manifest pull unavailable, robot_code={}, status={}",
                        robotCode, result == null ? "no-reply" : result.getStatus());
                return Optional.empty();
            }
            List<RobotVersionManifestEntry> manifest = otaManifestFetchMapstruct.toEntries(
                    result.getData().report().manifest());
            return Optional.of(new RobotManifestReport(
                    robotCode, manifest, result.getData().reportedAt()));
        } catch (RuntimeException exception) {
            log.info("manifest pull failed, robot_code={}", robotCode, exception);
            return Optional.empty();
        }
    }
}
