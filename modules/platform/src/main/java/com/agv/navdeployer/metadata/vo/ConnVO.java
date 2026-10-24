package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapInterFloorConn;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/** 楼层间连接视图（connections 从存库 JSON 解析，元素为楼层/点位编码）。 */
public record ConnVO(Long id,
                     Long mapId,
                     String connCode,
                     String name,
                     String connType,
                     List<ConnEndpointVO> connections,
                     Double capacity,
                     Double maxSpeed,
                     ConnRampVO rampInfo) {

    private static final TypeReference<List<ConnEndpointVO>> CONNECTIONS_TYPE = new TypeReference<>() {
    };

    public static ConnVO from(MapInterFloorConn conn) {
        List<ConnEndpointVO> connections = ZoneVO.parseJson(conn.getConnections(), CONNECTIONS_TYPE);
        return new ConnVO(conn.getId(), conn.getMapId(), conn.getConnCode(), conn.getName(),
                conn.getConnType(), connections == null ? List.of() : connections,
                conn.getCapacity(), conn.getMaxSpeed(),
                ZoneVO.parseJson(conn.getRampInfo(), ConnRampVO.class));
    }
}
