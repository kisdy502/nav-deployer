package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapInterFloorConn;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

public record ConnDTO(Long id,
                     Long mapId,
                     String connCode,
                     String name,
                     String connType,
                     List<ConnEndpointDTO> connections,
                     Double capacity,
                     Double maxSpeed,
                     ConnRampDTO rampInfo) {

    private static final TypeReference<List<ConnEndpointDTO>> CONNECTIONS_TYPE = new TypeReference<>() {
    };

    public static ConnDTO from(MapInterFloorConn conn) {
        List<ConnEndpointDTO> connections = ZoneDTO.parseJson(conn.getConnections(), CONNECTIONS_TYPE);
        return new ConnDTO(conn.getId(), conn.getMapId(), conn.getConnCode(), conn.getName(),
                conn.getConnType(), connections == null ? List.of() : connections,
                conn.getCapacity(), conn.getMaxSpeed(),
                ZoneDTO.parseJson(conn.getRampInfo(), ConnRampDTO.class));
    }
}
