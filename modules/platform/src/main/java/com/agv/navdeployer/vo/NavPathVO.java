package com.agv.navdeployer.vo;

import com.agv.navdeployer.entity.NavPath;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NavPathVO {

    private Long id;

    private Long mapId;

    private String pathCode;

    private String pathName;

    private String status;

    /** 有序边列表（列表接口不下发，详情接口下发）。 */
    private List<PathEdgeVO> edges;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public static NavPathVO from(NavPath path) {
        return new NavPathVO(path.getId(), path.getMapId(), path.getPathCode(), path.getPathName(),
                path.getStatus(), null, path.getCreatedAt(), path.getUpdatedAt());
    }

    public static NavPathVO from(NavPath path, List<PathEdgeVO> edges) {
        return new NavPathVO(path.getId(), path.getMapId(), path.getPathCode(), path.getPathName(),
                path.getStatus(), edges, path.getCreatedAt(), path.getUpdatedAt());
    }
}
