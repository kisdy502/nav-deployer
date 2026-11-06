package com.zioneer.robotmanager.robot.ota.interfaces;

import com.zioneer.robotmanager.common.core.result.Result;
import com.zioneer.robotmanager.robot.util.SnowflakeIdParser;
import com.zioneer.robotmanager.robot.ota.application.OtaUpgradeTaskService;
import com.zioneer.robotmanager.robot.ota.application.dto.ManifestVerificationVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaUpgradeTaskVO;
import com.zioneer.robotmanager.robot.ota.application.dto.PendingActionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@Tag(name = "OTA升级任务", description = "提供升级任务的查询能力")
@RequestMapping("/api/v1/ota")
public class OtaUpgradeTaskController {

    private final OtaUpgradeTaskService otaUpgradeTaskService;

    public OtaUpgradeTaskController(OtaUpgradeTaskService otaUpgradeTaskService) {
        this.otaUpgradeTaskService = otaUpgradeTaskService;
    }

    @GetMapping("/release/{id}/upgrade-tasks")
    @Operation(summary = "查询发布下的升级任务")
    public Result<List<OtaUpgradeTaskVO>> listByRelease(@PathVariable String id) {
        return Result.ok(otaUpgradeTaskService.upgradeTasks(SnowflakeIdParser.parse(id)));
    }

    @GetMapping("/robots/{robotCode}/upgrade-tasks/latest")
    @Operation(summary = "查询机器人每种类型最新待执行的升级 Action")
    public Result<List<PendingActionVO>> latestPendingUpgradeActions(@PathVariable @NotBlank String robotCode) {
        return Result.ok(otaUpgradeTaskService.latestPendingUpgradeActions(robotCode));
    }

    @PostMapping("/upgrade-tasks/{id}/manifest-verification")
    @Operation(summary = "对未终结升级任务发起清单核验")
    public Result<ManifestVerificationVO> verifyManifest(@PathVariable String id) {
        return Result.ok(otaUpgradeTaskService.verifyManifest(SnowflakeIdParser.parse(id)));
    }
}
