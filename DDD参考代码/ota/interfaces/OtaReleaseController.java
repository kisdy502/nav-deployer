package com.zioneer.robotmanager.robot.ota.interfaces;

import com.zioneer.robotmanager.common.core.result.PageResult;
import com.zioneer.robotmanager.common.core.result.Result;
import com.zioneer.robotmanager.robot.util.SnowflakeIdParser;
import com.zioneer.robotmanager.robot.ota.application.OtaReleaseService;
import com.zioneer.robotmanager.robot.ota.application.cmd.CreateOtaReleaseCmd;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaReleaseDetailVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaReleaseVO;
import com.zioneer.robotmanager.robot.ota.application.query.OtaReleaseQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "OTA发布管理", description = "提供 OTA 发布的创建、列表与进度查询能力")
@RequestMapping("/api/v1/ota/release")
public class OtaReleaseController {

    private final OtaReleaseService otaReleaseService;

    public OtaReleaseController(OtaReleaseService otaReleaseService) {
        this.otaReleaseService = otaReleaseService;
    }

    @PostMapping
    @Operation(summary = "创建发布")
    public Result<String> create(@Valid @RequestBody CreateOtaReleaseCmd cmd) {
        return Result.ok(otaReleaseService.create(cmd));
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询发布")
    public Result<PageResult<OtaReleaseVO>> page(@Valid @RequestBody OtaReleaseQuery query) {
        return Result.ok(otaReleaseService.page(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询发布进度头部")
    public Result<OtaReleaseDetailVO> detail(@PathVariable String id) {
        return Result.ok(otaReleaseService.detail(SnowflakeIdParser.parse(id)));
    }
}
