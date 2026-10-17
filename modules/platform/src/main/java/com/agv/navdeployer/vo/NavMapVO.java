package com.agv.navdeployer.vo;

import com.agv.navdeployer.entity.NavMap;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NavMapVO {

    private Long id;

    private String mapName;

    private String status;

    private String source;

    /** 机器人侧地图名（maps_dir 三件套文件名主干；空则切图时回退用 mapName）。 */
    private String robotMapName;

    private Double resolution;

    private Integer width;

    private Integer height;

    private Double originX;

    private Double originY;

    private Double originYaw;

    private Long dataSize;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static NavMapVO from(NavMap map) {
        return new NavMapVO(map.getId(), map.getMapName(), map.getStatus(), map.getSource(),
                map.getRobotMapName(),
                map.getResolution(), map.getWidth(), map.getHeight(),
                map.getOriginX(), map.getOriginY(), map.getOriginYaw(), map.getDataSize(),
                map.getCreatedAt(), map.getUpdatedAt());
    }
}
