package com.agv.navdeployer.metadata.vo;

import java.util.List;

/** 地图全量树（编辑器一次拉取：地图 + 各楼楼层全量 + 楼层间连接）。 */
public record MapFullVO(MapV2VO map,
                        List<FloorBundleVO> floors,
                        List<ConnVO> connections) {
}
