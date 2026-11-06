package com.agv.navdeployer.vo;

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

    private List<PathEdgeVO> edges;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
