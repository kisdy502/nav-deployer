package com.agv.navdeployer.metadata.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 路线保存（新建/更新共用；更新时 mapId/floorId/pathCode 被忽略）。
 * waypointIds 为有序途经点位链（≥2）：新建必填（Service 校验），
 * 更新时缺省 = 仅改元属性（名称/双向/限速/避障/状态等，段保持不动，部署/停用快捷操作即此用法）；
 * 更新携带时整链替换并按 segments（缺省直线）重建段。
 * segments 提供时必须与点位链一一对应（合并旧格式的曲线/控制点/倒车等段级能力）。
 */
public record PathSaveDTO(
        Long mapId,
        Long floorId,
        @Size(max = 64) String pathCode,
        @Size(max = 128) String name,
        List<Long> waypointIds,
        Boolean isBidirectional,
        Double maxSpeed,
        Boolean enableObstacleAvoidance,
        List<PathSegmentDTO> segments,
        @Size(max = 16) String status,
        @Size(max = 64) String modifiedBy) {
}
