package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 货架保存（新建/更新共用）。
 * vertices：新建必填（Service 校验 ≥3 顶点）；更新缺省 = 保留现有占地轮廓
 * （属性面板整树保存只改名称/高度/层格位，轮廓由画布拖拽顶点/中心手柄更新）。
 * layers 提供时整树替换；更新时缺省 = 保留现有层/格位。
 */
public record ShelfSaveDTO(
        Long mapId,
        Long floorId,
        @Size(max = 64) String shelfCode,
        @Size(max = 128) String name,
        List<double[]> vertices,
        Double height,
        List<ShelfEdgeDTO> edges,
        List<ShelfLayerDTO> layers,
        @Size(max = 64) String modifiedBy) {
}
