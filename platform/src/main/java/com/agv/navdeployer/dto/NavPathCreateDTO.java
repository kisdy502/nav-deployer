package com.agv.navdeployer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NavPathCreateDTO(
        @NotNull(message = "map_id 不能为空") Long mapId,
        @NotBlank(message = "路线编码不能为空") @Size(max = 64) String pathCode,
        @Size(max = 128) String pathName
) {
}
