package com.zioneer.robotmanager.robot.ota.application.dto;

import com.zioneer.robotmanager.robot.ota.domain.otamanifest.OtaManifestMismatch;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
public class ManifestVerificationVO {

    @Schema(description = "机器人是否可达并返回了清单")
    private boolean robotReachable;

    @Schema(description = "未达标节点明细，满足或机器人不可达时为空")
    private List<OtaManifestMismatch> mismatches;

    @Schema(description = "核验时刻")
    private Instant verifiedAt;

    public ManifestVerificationVO(boolean robotReachable, List<OtaManifestMismatch> mismatches, Instant verifiedAt) {
        this.robotReachable = robotReachable;
        this.mismatches = mismatches;
        this.verifiedAt = verifiedAt;
    }
}
