package com.zioneer.robotmanager.robot.ota.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "平台代下载短效票据")
public record OtaDownloadTicketVO(
        @Schema(description = "票据，拼到代下载接口的 ticket 查询参数") String ticket,
        @Schema(description = "有效期（秒）") long expiresIn
) {
}
