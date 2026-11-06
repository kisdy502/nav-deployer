package com.zioneer.robotmanager.robot.ota.interfaces;

import com.zioneer.robotmanager.common.core.result.PageResult;
import com.zioneer.robotmanager.common.core.result.Result;
import com.zioneer.robotmanager.robot.ota.application.OtaDownloadTicketIssuer;
import com.zioneer.robotmanager.robot.ota.application.OtaPackageService;
import com.zioneer.robotmanager.robot.ota.application.cmd.OtaPackageCmd;
import com.zioneer.robotmanager.robot.ota.application.cmd.UpdateOtaPackageCmd;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaDownloadTicketVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaPackageVO;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaSourceDownload;
import com.zioneer.robotmanager.robot.ota.application.dto.OtaSourceDownloadUrlVO;
import com.zioneer.robotmanager.robot.ota.application.query.OtaPackageQuery;
import com.zioneer.robotmanager.robot.util.SnowflakeIdParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

@RestController
@Validated
@Tag(name = "OTA安装包管理", description = "提供安装包的分页查询、新增、修改、删除与下载地址获取能力")
@RequestMapping("/api/v1/ota/package")
public class OtaPackageController {

    private final OtaPackageService otaPackageService;
    private final OtaDownloadTicketIssuer otaDownloadTicketIssuer;


    public OtaPackageController(OtaPackageService otaPackageService, OtaDownloadTicketIssuer otaDownloadTicketIssuer) {
        this.otaPackageService = otaPackageService;
        this.otaDownloadTicketIssuer = otaDownloadTicketIssuer;
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询安装包")
    public Result<PageResult<OtaPackageVO>> page(@Valid @RequestBody OtaPackageQuery query) {
        return Result.ok(otaPackageService.page(query));
    }

    @PostMapping
    @Operation(summary = "新增安装包")
    public Result<String> create(@Valid @RequestBody OtaPackageCmd cmd) {
        return Result.ok(otaPackageService.create(cmd));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改安装包")
    public Result<Void> update(@PathVariable String id, @Valid @RequestBody UpdateOtaPackageCmd cmd) {
        otaPackageService.update(SnowflakeIdParser.parse(id), cmd);
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除安装包")
    public Result<Void> delete(@PathVariable String id) {
        otaPackageService.delete(SnowflakeIdParser.parse(id));
        return Result.ok();
    }

    @GetMapping("/{id}/download-url")
    @Operation(summary = "获取安装包全部来源的下载地址")
    public Result<List<OtaSourceDownloadUrlVO>> downloadUrls(@PathVariable String id) {
        return Result.ok(otaPackageService.downloadUrls(SnowflakeIdParser.parse(id)));
    }

    @GetMapping("/{id}/sources/{sourceKey}/download-ticket")
    @Operation(summary = "换取平台代下载短效票据（默认 60 秒，拼到 download 接口的 ticket 参数）")
    public Result<OtaDownloadTicketVO> downloadTicket(@PathVariable String id, @PathVariable String sourceKey) {
        return Result.ok(otaPackageService.issueDownloadTicket(SnowflakeIdParser.parse(id), sourceKey));
    }

    @GetMapping("/{id}/sources/{sourceKey}/download")
    @Operation(summary = "下载安装包来源制品（平台代理，支持 Range 断点续传；凭 ticket 参数鉴权）")
    public ResponseEntity<StreamingResponseBody> downloadSource(
            @PathVariable String id,
            @PathVariable String sourceKey,
            @RequestHeader(name = HttpHeaders.RANGE, required = false) String rangeHeader,
            @RequestParam(name = "ticket", required = false) String ticket) {
        otaDownloadTicketIssuer.validate(ticket, id, sourceKey);
        OtaSourceDownload download = otaPackageService.openSourceDownload(
                SnowflakeIdParser.parse(id), sourceKey, rangeHeader);
        try {
            ResponseEntity.BodyBuilder response = ResponseEntity.status(download.statusCode())
                    .header(HttpHeaders.CONTENT_DISPOSITION, download.contentDisposition())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes");
            if (download.contentType() != null) {
                response = response.contentType(MediaType.parseMediaType(download.contentType()));
            }
            if (download.contentLength() != null) {
                response = response.contentLength(download.contentLength());
            }
            if (download.contentRange() != null) {
                response = response.header(HttpHeaders.CONTENT_RANGE, download.contentRange());
            }
            return response.body(download.asStreamingBody());
        } catch (RuntimeException exception) {
            download.closeQuietly();
            throw exception;
        }
    }
}
