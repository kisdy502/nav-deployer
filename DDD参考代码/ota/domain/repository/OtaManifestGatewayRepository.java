package com.zioneer.robotmanager.robot.ota.domain.repository;

import com.zioneer.robotmanager.robot.domain.model.RobotManifestReport;

import java.util.Optional;

/** 经接入网关向机器人拉取当前版本清单的出口；机器人不可达或应答无效时返回空。 */
public interface OtaManifestGatewayRepository {

    Optional<RobotManifestReport> findCurrentManifest(String robotCode);
}
