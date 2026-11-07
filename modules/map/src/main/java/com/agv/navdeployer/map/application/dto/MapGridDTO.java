package com.agv.navdeployer.map.application.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MapGridDTO {

    private String frameId;

    private Double resolution;

    private Integer width;

    private Integer height;

    private Double originX;

    private Double originY;

    private Double originYaw;

    private int[] data;

    private Instant receivedAt;
}
