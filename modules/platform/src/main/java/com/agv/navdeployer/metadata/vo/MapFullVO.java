package com.agv.navdeployer.metadata.vo;

import java.util.List;

public record MapFullVO(MapV2VO map,
                        List<FloorBundleVO> floors,
                        List<ConnVO> connections) {
}
