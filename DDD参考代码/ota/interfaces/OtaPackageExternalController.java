package com.zioneer.robotmanager.robot.ota.interfaces;

import com.zioneer.robotmanager.common.core.result.Result;
import com.zioneer.robotmanager.robot.ota.application.OtaPackageService;
import com.zioneer.robotmanager.robot.ota.application.cmd.OtaPackageCmd;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@Tag(name = "外部接口：OTA安装包登记（CI/CD）", description = "供 CI/CD 流水线调用的安装包登记入口，上传者固定为 jenkins")
@RequestMapping("/api/v1/ota/external/package")
public class OtaPackageExternalController {

    private final OtaPackageService otaPackageService;

    public OtaPackageExternalController(OtaPackageService otaPackageService) {
        this.otaPackageService = otaPackageService;
    }

    @PostMapping
    @Operation(summary = "登记安装包")
    public Result<String> create(@Valid @RequestBody OtaPackageCmd cmd) {
        return Result.ok(otaPackageService.createByCi(cmd));
    }
}
