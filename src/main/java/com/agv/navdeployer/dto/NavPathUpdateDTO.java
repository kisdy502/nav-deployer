package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 更新路线基本信息（边序列走 PUT /{id}/edges）。 */
public record NavPathUpdateDTO(
        @Size(max = 128) String pathName,
        @Pattern(regexp = "DRAFT|DISABLED", message = "status 只能是 DRAFT/DISABLED") String status
) {
}
