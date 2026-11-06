package com.agv.navdeployer.metadata.vo;

import com.agv.navdeployer.metadata.entity.MapShelf;

import java.util.List;

public record ShelfVO(Long id,
                      Long mapId,
                      Long floorId,
                      String shelfCode,
                      String name,
                      List<double[]> vertices,
                      Double height,
                      List<ShelfEdgeVO> edges,
                      List<ShelfLayerVO> layers) {

    public static ShelfVO from(MapShelf shelf, List<ShelfEdgeVO> edges, List<ShelfLayerVO> layers) {
        return new ShelfVO(shelf.getId(), shelf.getMapId(), shelf.getFloorId(), shelf.getShelfCode(),
                shelf.getName(), ZoneVO.parseVertices(shelf.getVertices()), shelf.getHeight(),
                edges, layers);
    }
}
