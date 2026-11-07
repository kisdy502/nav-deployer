package com.agv.navdeployer.map.application.dto;

import com.agv.navdeployer.map.domain.MapShelf;

import java.util.List;

public record ShelfDTO(Long id,
                      Long mapId,
                      Long floorId,
                      String shelfCode,
                      String name,
                      List<double[]> vertices,
                      Double height,
                      List<ShelfEdgeDTO> edges,
                      List<ShelfLayerDTO> layers) {

    public static ShelfDTO from(MapShelf shelf, List<ShelfEdgeDTO> edges, List<ShelfLayerDTO> layers) {
        return new ShelfDTO(shelf.getId(), shelf.getMapId(), shelf.getFloorId(), shelf.getShelfCode(),
                shelf.getName(), ZoneDTO.parseVertices(shelf.getVertices()), shelf.getHeight(),
                edges, layers);
    }
}
