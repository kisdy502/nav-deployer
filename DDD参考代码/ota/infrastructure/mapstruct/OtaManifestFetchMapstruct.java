package com.zioneer.robotmanager.robot.ota.infrastructure.mapstruct;

import com.zioneer.robotmanager.connectivity.ota.OtaManifestItem;
import com.zioneer.robotmanager.robot.domain.model.RobotVersionManifestEntry;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface OtaManifestFetchMapstruct {

    List<RobotVersionManifestEntry> toEntries(List<OtaManifestItem> items);

    RobotVersionManifestEntry toEntry(OtaManifestItem item);
}
