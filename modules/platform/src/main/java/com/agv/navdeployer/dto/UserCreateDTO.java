package com.agv.navdeployer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UserCreateDTO(
        @NotBlank(message = "name 不能为空")
        @Size(max = 64, message = "name 长度不能超过 64")
        String name,

        @NotNull(message = "age 不能为空")
        @Min(value = 0, message = "age 不能小于 0")
        @Max(value = 150, message = "age 不能大于 150")
        Integer age
) {
}
