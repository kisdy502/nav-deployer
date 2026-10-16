package com.agv.navdeployer.vo;

import com.agv.navdeployer.entity.NavPoint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NavPointVO {

    private Long id;

    private Long mapId;

    private String pointCode;

    private String pointType;

    private Double x;

    private Double y;

    private Double yaw;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static NavPointVO from(NavPoint point) {
        return new NavPointVO(point.getId(), point.getMapId(), point.getPointCode(), point.getPointType(),
                point.getX(), point.getY(), point.getYaw(), point.getRemark(),
                point.getCreatedAt(), point.getUpdatedAt());
    }
}
