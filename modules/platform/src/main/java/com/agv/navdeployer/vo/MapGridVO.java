package com.agv.navdeployer.vo;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** 实时/已存栅格的统一视图（/map 的 msg 结构展平，前端按行优先、y 翻转渲染）。 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MapGridVO {

    private String frameId;

    private Double resolution;

    private Integer width;

    private Integer height;

    private Double originX;

    private Double originY;

    private Double originYaw;

    /** -1 未知 / 0 空闲 / 1~100 占据，行优先，第 0 行在世界坐标最下方。 */
    private int[] data;

    private Instant receivedAt;
}
