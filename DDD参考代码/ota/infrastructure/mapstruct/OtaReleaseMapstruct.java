package com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct;

import com.zioneer.robotmanager.robot.domain.entity.RobotEntity;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaReleaseDetailVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaReleaseVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaUpgradeTaskVO;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaRelease;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaReleaseProgress;
import com.zioneer.robotmanager.robot.ota.domain.otarelease.OtaTargetDimension;
import com.zioneer.robotmanager.robot.ota.domain.otaupgradetask.OtaUpgradeTask;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OtaReleaseMapstruct {

    @Mapping(target = "id", expression = "java(toStringId(release.getId()))")
    @Mapping(target = "packageName", source = "release.packageSnapshot.name")
    @Mapping(target = "packageVersion", source = "release.packageSnapshot.version")
    @Mapping(target = "targetLabels", expression = "java(resolveTargetLabels(release, robotNames))")
    @Mapping(target = "status", source = "progress.status")
    @Mapping(target = "total", source = "progress.total")
    @Mapping(target = "succeeded", source = "progress.succeeded")
    @Mapping(target = "failed", source = "progress.failed")
    OtaReleaseVO toReleaseVO(OtaRelease release, OtaReleaseProgress progress, Map<String, String> robotNames);

    @Mapping(target = "id", expression = "java(toStringId(release.getId()))")
    @Mapping(target = "packageName", source = "release.packageSnapshot.name")
    @Mapping(target = "packageKind", source = "release.packageSnapshot.kind")
    @Mapping(target = "packageVersion", source = "release.packageSnapshot.version")
    @Mapping(target = "targetLabels", expression = "java(resolveTargetLabels(release, robotNames))")
    @Mapping(target = "status", source = "progress.status")
    @Mapping(target = "total", source = "progress.total")
    @Mapping(target = "pending", source = "progress.pending")
    @Mapping(target = "inProgress", source = "progress.inProgress")
    @Mapping(target = "succeeded", source = "progress.succeeded")
    @Mapping(target = "failed", source = "progress.failed")
    OtaReleaseDetailVO toReleaseDetailVO(OtaRelease release, OtaReleaseProgress progress, Map<String, String> robotNames);

    @Mapping(target = "id", expression = "java(toStringId(task.getId()))")
    @Mapping(target = "targetName", expression = "java(resolveTargetName(task, robot))")
    @Mapping(target = "robotCode", expression = "java(resolveRobotCode(robot))")
    @Mapping(target = "robotTypeNo", expression = "java(resolveRobotTypeNo(robot))")
    @Mapping(target = "status", source = "task.status")
    @Mapping(target = "createdAt", source = "task.createdAt")
    @Mapping(target = "packageManifest", source = "task.packageSnapshot.manifest")
    @Mapping(target = "currentManifest", source = "task.currentManifestSnapshot")
    OtaUpgradeTaskVO toUpgradeTaskVO(OtaUpgradeTask task, RobotEntity robot);

    default String toStringId(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    default List<String> resolveTargetLabels(OtaRelease release, Map<String, String> robotNames) {
        List<String> values = release.getTargetValues();
        if (values == null) {
            return List.of();
        }
        if (release.getTargetDimension() != OtaTargetDimension.ROBOT) {
            return values;
        }
        return values.stream().map(value -> robotNames.getOrDefault(value, value)).toList();
    }

    default String resolveTargetName(OtaUpgradeTask task, RobotEntity robot) {
        return robot == null ? task.getTargetId() : robot.getRobotName();
    }

    default String resolveRobotTypeNo(RobotEntity robot) {
        return robot == null ? null : robot.getRobotTypeNo();
    }

    default String resolveRobotCode(RobotEntity robot) {
        return robot == null ? null : robot.getRobotCode();
    }
}
