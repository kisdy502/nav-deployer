package com.agv.navdeployer.metadata.service;

import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.metadata.dto.ShelfEdgeDTO;
import com.agv.navdeployer.metadata.dto.ShelfLayerDTO;
import com.agv.navdeployer.metadata.dto.ShelfSaveDTO;
import com.agv.navdeployer.metadata.dto.ShelfSlotDTO;
import com.agv.navdeployer.metadata.entity.MapFloor;
import com.agv.navdeployer.metadata.entity.MapShelf;
import com.agv.navdeployer.metadata.entity.MapShelfLayer;
import com.agv.navdeployer.metadata.entity.MapShelfSlot;
import com.agv.navdeployer.metadata.mapper.MapFloorMapper;
import com.agv.navdeployer.metadata.mapper.MapShelfLayerMapper;
import com.agv.navdeployer.metadata.mapper.MapShelfMapper;
import com.agv.navdeployer.metadata.mapper.MapShelfSlotMapper;
import com.agv.navdeployer.metadata.vo.ShelfEdgeVO;
import com.agv.navdeployer.metadata.vo.ShelfLayerVO;
import com.agv.navdeployer.metadata.vo.ShelfSlotVO;
import com.agv.navdeployer.metadata.vo.ShelfVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 货架管理：整树保存（footprint + 层 + 格位一起提交一起替换）。 */
@Service
public class MapShelfService {

    private static final double[] DEFAULT_SLOT_SIZE = {0.4, 0.6, 0.3};

    private final MapShelfMapper shelfMapper;
    private final MapShelfLayerMapper layerMapper;
    private final MapShelfSlotMapper slotMapper;
    private final MapFloorMapper floorMapper;
    private final MapVersionTicker ticker;

    public MapShelfService(MapShelfMapper shelfMapper,
                           MapShelfLayerMapper layerMapper,
                           MapShelfSlotMapper slotMapper,
                           MapFloorMapper floorMapper,
                           MapVersionTicker ticker) {
        this.shelfMapper = shelfMapper;
        this.layerMapper = layerMapper;
        this.slotMapper = slotMapper;
        this.floorMapper = floorMapper;
        this.ticker = ticker;
    }

    @Transactional(rollbackFor = Exception.class)
    public ShelfVO create(ShelfSaveDTO dto) {
        if (dto.mapId() == null || dto.floorId() == null) {
            throw new IllegalArgumentException("新建货架必须携带 mapId 与 floorId");
        }
        if (dto.shelfCode() == null || dto.shelfCode().isBlank()) {
            throw new IllegalArgumentException("新建货架必须携带 shelfCode");
        }
        requireFloorOfMap(dto.floorId(), dto.mapId());
        String code = dto.shelfCode().trim();
        if (shelfMapper.selectCount(Wrappers.lambdaQuery(MapShelf.class)
                .eq(MapShelf::getMapId, dto.mapId())
                .eq(MapShelf::getShelfCode, code)) > 0) {
            throw new IllegalStateException("货架编码已存在: " + code);
        }
        MapShelf shelf = new MapShelf();
        shelf.setMapId(dto.mapId());
        shelf.setFloorId(dto.floorId());
        shelf.setShelfCode(code);
        applyFields(shelf, dto);
        shelfMapper.insert(shelf);
        insertLayers(shelf, dto.layers());
        ticker.touch(dto.mapId(), dto.modifiedBy());
        return get(shelf.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public ShelfVO update(Long id, ShelfSaveDTO dto) {
        MapShelf shelf = requireShelf(id);
        // 元属性：缺省不动
        if (dto.name() != null) {
            shelf.setName(dto.name().isBlank() ? shelf.getShelfCode() : dto.name().trim());
        }
        if (dto.height() != null) {
            shelf.setHeight(dto.height());
        }
        // 占地轮廓：携带时才替换（画布拖拽顶点/中心手柄的保存路径会带；属性面板整树保存不带=保留轮廓）
        if (dto.vertices() != null && !dto.vertices().isEmpty()) {
            applyVertices(shelf, dto);
        }
        shelf.setUpdatedAt(null);
        shelfMapper.updateById(shelf);
        // 层/格位：提供时整树替换；缺省保留（避免仅改名称/高度时误删层）
        if (dto.layers() != null) {
            layerMapper.delete(Wrappers.lambdaQuery(MapShelfLayer.class)
                    .eq(MapShelfLayer::getShelfId, id));
            insertLayers(shelf, dto.layers());
        }
        ticker.touch(shelf.getMapId(), dto.modifiedBy());
        return get(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapShelf shelf = requireShelf(id);
        shelfMapper.deleteById(id); // 层/格位经外键级联删除
        ticker.touch(shelf.getMapId(), null);
    }

    /** 货架整树详情。 */
    public ShelfVO get(Long id) {
        MapShelf shelf = requireShelf(id);
        List<ShelfLayerVO> layers = loadLayers(List.of(id)).get(id);
        return ShelfVO.from(shelf, parseEdges(shelf.getEdges()), layers == null ? List.of() : layers);
    }

    public List<ShelfVO> list(Long mapId) {
        List<MapShelf> shelves = shelfMapper.selectList(Wrappers.lambdaQuery(MapShelf.class)
                .eq(MapShelf::getMapId, mapId).orderByAsc(MapShelf::getId));
        return assemble(shelves);
    }

    public List<ShelfVO> listByFloor(Long floorId) {
        List<MapShelf> shelves = shelfMapper.selectList(Wrappers.lambdaQuery(MapShelf.class)
                .eq(MapShelf::getFloorId, floorId).orderByAsc(MapShelf::getId));
        return assemble(shelves);
    }

    private List<ShelfVO> assemble(List<MapShelf> shelves) {
        if (shelves.isEmpty()) {
            return List.of();
        }
        List<Long> shelfIds = shelves.stream().map(MapShelf::getId).toList();
        Map<Long, List<ShelfLayerVO>> layersByShelf = loadLayers(shelfIds);
        return shelves.stream()
                .map(shelf -> ShelfVO.from(shelf, parseEdges(shelf.getEdges()),
                        layersByShelf.getOrDefault(shelf.getId(), List.of())))
                .toList();
    }

    private Map<Long, List<ShelfLayerVO>> loadLayers(List<Long> shelfIds) {
        List<MapShelfLayer> layers = layerMapper.selectList(Wrappers.lambdaQuery(MapShelfLayer.class)
                .in(MapShelfLayer::getShelfId, shelfIds)
                .orderByAsc(MapShelfLayer::getLayerIndex));
        if (layers.isEmpty()) {
            return Map.of();
        }
        List<Long> layerIds = layers.stream().map(MapShelfLayer::getId).toList();
        Map<Long, List<MapShelfSlot>> slotsByLayer = slotMapper.selectList(
                        Wrappers.lambdaQuery(MapShelfSlot.class)
                                .in(MapShelfSlot::getLayerId, layerIds)
                                .orderByAsc(MapShelfSlot::getSlotIndex))
                .stream().collect(Collectors.groupingBy(MapShelfSlot::getLayerId));
        Map<Long, List<ShelfLayerVO>> result = new java.util.LinkedHashMap<>();
        for (MapShelfLayer layer : layers) {
            List<ShelfSlotVO> slots = slotsByLayer.getOrDefault(layer.getId(), List.of()).stream()
                    .map(slot -> new ShelfSlotVO(slot.getId(), slot.getSlotIndex(),
                            new double[]{slot.getX(), slot.getY(), slot.getZ()},
                            new double[]{slot.getWidth(), slot.getDepth(), slot.getHeight()},
                            slot.getProductId(), slot.getProductName()))
                    .toList();
            result.computeIfAbsent(layer.getShelfId(), key -> new ArrayList<>())
                    .add(new ShelfLayerVO(layer.getId(), layer.getLayerIndex(), layer.getElevation(),
                            layer.getAccessEdge(), layer.getNumSlots(), slots));
        }
        return result;
    }

    /** 新建：全字段应用（vertices 必填校验在此）。 */
    private void applyFields(MapShelf shelf, ShelfSaveDTO dto) {
        applyVertices(shelf, dto);
        shelf.setName(dto.name() == null || dto.name().isBlank()
                ? shelf.getShelfCode() : dto.name().trim());
        shelf.setHeight(dto.height() != null ? dto.height() : 1.8);
    }

    /** 校验并应用占地多边形 + 边定义（vertices 必填 ≥3）。 */
    private void applyVertices(MapShelf shelf, ShelfSaveDTO dto) {
        if (dto.vertices() == null || dto.vertices().size() < 3) {
            throw new IllegalArgumentException("货架占地多边形至少需要 3 个顶点");
        }
        for (double[] vertex : dto.vertices()) {
            if (vertex == null || vertex.length != 2) {
                throw new IllegalArgumentException("货架顶点必须是 [x,y] 二维坐标");
            }
        }
        shelf.setVertices(MapZoneService.toJson(dto.vertices()));
        shelf.setEdges(MapZoneService.toJson(normalizeEdges(dto.edges(), dto.vertices().size())));
    }

    /** edges 缺省时按顶点顺序自动生成闭环边（0→1→2→…→n-1→0）。 */
    private List<ShelfEdgeDTO> normalizeEdges(List<ShelfEdgeDTO> edges, int vertexCount) {
        if (edges == null || edges.isEmpty()) {
            List<ShelfEdgeDTO> auto = new ArrayList<>(vertexCount);
            for (int i = 0; i < vertexCount; i++) {
                auto.add(new ShelfEdgeDTO(i, i, (i + 1) % vertexCount, null));
            }
            return auto;
        }
        for (ShelfEdgeDTO edge : edges) {
            if (edge.vertexStart() < 0 || edge.vertexStart() >= vertexCount
                    || edge.vertexEnd() < 0 || edge.vertexEnd() >= vertexCount) {
                throw new IllegalArgumentException("货架边引用了不存在的顶点索引: edgeIndex="
                        + edge.edgeIndex() + "（共 " + vertexCount + " 个顶点）");
            }
        }
        return edges;
    }

    private List<ShelfEdgeVO> parseEdges(String edgesJson) {
        if (edgesJson == null || edgesJson.isBlank()) {
            return List.of();
        }
        return com.agv.navdeployer.metadata.vo.ZoneVO.parseJson(edgesJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<ShelfEdgeVO>>() {
                });
    }

    private void insertLayers(MapShelf shelf, List<ShelfLayerDTO> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        int index = 0;
        for (ShelfLayerDTO layerDto : layers) {
            MapShelfLayer layer = new MapShelfLayer();
            layer.setShelfId(shelf.getId());
            layer.setLayerIndex(layerDto.layerIndex() != null ? layerDto.layerIndex() : index);
            layer.setElevation(layerDto.elevation());
            layer.setAccessEdge(layerDto.accessEdge() != null ? layerDto.accessEdge() : 0);
            List<ShelfSlotDTO> slots = layerDto.slots() == null ? List.of() : layerDto.slots();
            layer.setNumSlots(slots.size());
            layerMapper.insert(layer);
            for (ShelfSlotDTO slotDto : slots) {
                MapShelfSlot slot = new MapShelfSlot();
                slot.setLayerId(layer.getId());
                slot.setShelfId(shelf.getId());
                slot.setSlotIndex(slotDto.slotIndex());
                double[] position = slotDto.position();
                if (position == null || position.length != 3) {
                    throw new IllegalArgumentException("格位 position 必须是 [x,y,z] 三维坐标");
                }
                slot.setX(position[0]);
                slot.setY(position[1]);
                slot.setZ(position[2]);
                double[] size = slotDto.size() == null || slotDto.size().length != 3
                        ? DEFAULT_SLOT_SIZE : slotDto.size();
                slot.setWidth(size[0]);
                slot.setDepth(size[1]);
                slot.setHeight(size[2]);
                slot.setProductId(slotDto.productId());
                slot.setProductName(slotDto.productName());
                slotMapper.insert(slot);
            }
            index++;
        }
    }

    private MapShelf requireShelf(Long id) {
        MapShelf shelf = shelfMapper.selectById(id);
        if (shelf == null) {
            throw new NotFoundException("货架 " + id + " 不存在");
        }
        return shelf;
    }

    private void requireFloorOfMap(Long floorId, Long mapId) {
        MapFloor floor = floorMapper.selectById(floorId);
        if (floor == null) {
            throw new NotFoundException("楼层 " + floorId + " 不存在");
        }
        if (!floor.getMapId().equals(mapId)) {
            throw new IllegalArgumentException("楼层 " + floorId + " 不属于地图 " + mapId);
        }
    }
}
