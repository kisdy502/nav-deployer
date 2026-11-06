package com.agv.navdeployer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record NavPathEdgesUpdateDTO(
        @NotEmpty(message = "edges 不能为空") List<@Valid PathEdgeDTO> edges
) {
}
