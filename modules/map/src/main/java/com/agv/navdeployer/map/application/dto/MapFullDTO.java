package com.agv.navdeployer.map.application.dto;

import java.util.List;

public record MapFullDTO(MapV2DTO map,
                        List<FloorBundleDTO> floors,
                        List<ConnDTO> connections) {
}
