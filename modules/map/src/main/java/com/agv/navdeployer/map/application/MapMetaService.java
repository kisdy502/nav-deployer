package com.agv.navdeployer.map.application;

import com.agv.navdeployer.common.error.NotFoundException;
import com.agv.navdeployer.map.application.MetadataJson;
import com.agv.navdeployer.map.application.cmd.MapCreateFromLiveCmd;
import com.agv.navdeployer.map.application.cmd.MapUpdateCmd;
import com.agv.navdeployer.map.domain.MapFloor;
import com.agv.navdeployer.map.domain.MapInterFloorConn;
import com.agv.navdeployer.map.domain.MapMeta;
import com.agv.navdeployer.map.domain.MapPath;
import com.agv.navdeployer.map.domain.MapPathSegment;
import com.agv.navdeployer.map.domain.MapShelf;
import com.agv.navdeployer.map.domain.MapShelfLayer;
import com.agv.navdeployer.map.domain.MapShelfSlot;
import com.agv.navdeployer.map.domain.MapWaypoint;
import com.agv.navdeployer.map.domain.MapZone;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapFloorMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapInterFloorConnMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapMetaMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapPathMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapPathSegmentMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapShelfLayerMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapShelfMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapShelfSlotMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapWaypointMapper;
import com.agv.navdeployer.map.infrastructure.repository.mapper.MapZoneMapper;
import com.agv.navdeployer.map.application.dto.ConnDTO;
import com.agv.navdeployer.map.application.dto.FloorBundleDTO;
import com.agv.navdeployer.map.application.dto.FloorDTO;
import com.agv.navdeployer.map.application.dto.MapFullDTO;
import com.agv.navdeployer.map.application.dto.MapV2DTO;
import com.agv.navdeployer.map.application.dto.PathV2DTO;
import com.agv.navdeployer.map.application.dto.ShelfDTO;
import com.agv.navdeployer.map.application.dto.WaypointDTO;
import com.agv.navdeployer.map.application.dto.ZoneDTO;
import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.sim.MapSubscriptionGuard;
import com.agv.navdeployer.map.application.dto.MapGridDTO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class MapMetaService {

    private static final Logger log = LoggerFactory.getLogger(MapMetaService.class);

    private final MapMetaMapper mapMetaMapper;
    private final MapFloorMapper floorMapper;
    private final MapWaypointMapper waypointMapper;
    private final MapZoneMapper zoneMapper;
    private final MapShelfMapper shelfMapper;
    private final MapShelfLayerMapper shelfLayerMapper;
    private final MapShelfSlotMapper shelfSlotMapper;
    private final MapPathMapper pathMapper;
    private final MapPathSegmentMapper pathSegmentMapper;
    private final MapInterFloorConnMapper connMapper;
    private final LiveMapCache liveMapCache;
    private final MapSubscriptionGuard mapSubscriptionGuard;
    private final GridObjectStore gridStore;
    private final com.fasterxml.jackson.databind.ObjectMapper webMapper;
    private final MapVersionTicker ticker;

    public MapMetaService(MapMetaMapper mapMetaMapper,
                          MapFloorMapper floorMapper,
                          MapWaypointMapper waypointMapper,
                          MapZoneMapper zoneMapper,
                          MapShelfMapper shelfMapper,
                          MapShelfLayerMapper shelfLayerMapper,
                          MapShelfSlotMapper shelfSlotMapper,
                          MapPathMapper pathMapper,
                          MapPathSegmentMapper pathSegmentMapper,
                          MapInterFloorConnMapper connMapper,
                          LiveMapCache liveMapCache,
                          MapSubscriptionGuard mapSubscriptionGuard,
                          GridObjectStore gridStore,
                          com.fasterxml.jackson.databind.ObjectMapper webMapper,
                          MapVersionTicker ticker) {
        this.mapMetaMapper = mapMetaMapper;
        this.floorMapper = floorMapper;
        this.waypointMapper = waypointMapper;
        this.zoneMapper = zoneMapper;
        this.shelfMapper = shelfMapper;
        this.shelfLayerMapper = shelfLayerMapper;
        this.shelfSlotMapper = shelfSlotMapper;
        this.pathMapper = pathMapper;
        this.pathSegmentMapper = pathSegmentMapper;
        this.connMapper = connMapper;
        this.liveMapCache = liveMapCache;
        this.mapSubscriptionGuard = mapSubscriptionGuard;
        this.gridStore = gridStore;
        this.webMapper = webMapper;
        this.ticker = ticker;
    }

    public List<MapV2DTO> list() {
        return mapMetaMapper.selectList(Wrappers.lambdaQuery(MapMeta.class)
                        .orderByDesc(MapMeta::getId))
                .stream().map(MapV2DTO::from).toList();
    }

    public MapV2DTO get(Long id) {
        return MapV2DTO.from(requireMap(id));
    }

    public MapMeta requireMap(Long id) {
        MapMeta map = mapMetaMapper.selectById(id);
        if (map == null) {
            throw new NotFoundException("地图 " + id + " 不存在");
        }
        return map;
    }

    public MapFullDTO loadFull(Long id) {
        MapMeta map = requireMap(id);
        List<MapFloor> floors = floorMapper.selectList(Wrappers.lambdaQuery(MapFloor.class)
                .eq(MapFloor::getMapId, id)
                .orderByAsc(MapFloor::getSortOrder).orderByAsc(MapFloor::getId));
        List<MapWaypoint> waypoints = waypointMapper.selectList(Wrappers.lambdaQuery(MapWaypoint.class)
                .eq(MapWaypoint::getMapId, id).orderByAsc(MapWaypoint::getId));
        List<MapZone> zones = zoneMapper.selectList(Wrappers.lambdaQuery(MapZone.class)
                .eq(MapZone::getMapId, id).orderByAsc(MapZone::getId));
        List<MapShelf> shelves = shelfMapper.selectList(Wrappers.lambdaQuery(MapShelf.class)
                .eq(MapShelf::getMapId, id).orderByAsc(MapShelf::getId));
        List<MapPath> paths = pathMapper.selectList(Wrappers.lambdaQuery(MapPath.class)
                .eq(MapPath::getMapId, id).orderByAsc(MapPath::getId));

        Map<Long, List<ShelfDTO>> shelfVosByFloor = loadShelfTree(shelves)
                .stream().collect(Collectors.groupingBy(ShelfDTO::floorId));
        Map<Long, List<PathV2DTO>> pathVosByFloor = new HashMap<>();
        for (MapPath path : paths) {
            pathVosByFloor.computeIfAbsent(path.getFloorId(), key -> new ArrayList<>())
                    .add(PathV2DTO.from(path,
                            pathSegmentMapper.selectList(Wrappers.lambdaQuery(MapPathSegment.class)
                                            .eq(MapPathSegment::getPathId, path.getId())
                                            .orderByAsc(MapPathSegment::getSeq))
                                    .stream().map(com.agv.navdeployer.map.application.dto.SegmentDTO::from)
                                    .toList()));
        }

        List<FloorBundleDTO> bundles = floors.stream().map(floor -> new FloorBundleDTO(
                FloorDTO.from(floor),
                waypoints.stream().filter(w -> w.getFloorId().equals(floor.getId()))
                        .map(WaypointDTO::from).toList(),
                zones.stream().filter(z -> z.getFloorId().equals(floor.getId()))
                        .map(ZoneDTO::from).toList(),
                shelfVosByFloor.getOrDefault(floor.getId(), List.of()),
                pathVosByFloor.getOrDefault(floor.getId(), List.of())))
                .toList();
        List<ConnDTO> conns = connMapper.selectList(Wrappers.lambdaQuery(MapInterFloorConn.class)
                        .eq(MapInterFloorConn::getMapId, id).orderByAsc(MapInterFloorConn::getId))
                .stream().map(ConnDTO::from).toList();
        return new MapFullDTO(MapV2DTO.from(map), bundles, conns);
    }

    private List<ShelfDTO> loadShelfTree(List<MapShelf> shelves) {
        if (shelves.isEmpty()) {
            return List.of();
        }
        List<Long> shelfIds = shelves.stream().map(MapShelf::getId).toList();
        List<MapShelfLayer> layers = shelfLayerMapper.selectList(Wrappers.lambdaQuery(MapShelfLayer.class)
                .in(MapShelfLayer::getShelfId, shelfIds).orderByAsc(MapShelfLayer::getLayerIndex));
        Map<Long, List<com.agv.navdeployer.map.application.dto.ShelfLayerDTO>> layerVosByShelf = new HashMap<>();
        for (MapShelfLayer layer : layers) {
            List<com.agv.navdeployer.map.application.dto.ShelfSlotDTO> slots = shelfSlotMapper
                    .selectList(Wrappers.lambdaQuery(MapShelfSlot.class)
                            .eq(MapShelfSlot::getLayerId, layer.getId())
                            .orderByAsc(MapShelfSlot::getSlotIndex))
                    .stream().map(slot -> new com.agv.navdeployer.map.application.dto.ShelfSlotDTO(
                            slot.getId(), slot.getSlotIndex(),
                            new double[]{slot.getX(), slot.getY(), slot.getZ()},
                            new double[]{slot.getWidth(), slot.getDepth(), slot.getHeight()},
                            slot.getProductId(), slot.getProductName()))
                    .toList();
            layerVosByShelf.computeIfAbsent(layer.getShelfId(), key -> new ArrayList<>())
                    .add(new com.agv.navdeployer.map.application.dto.ShelfLayerDTO(layer.getId(),
                            layer.getLayerIndex(), layer.getElevation(), layer.getAccessEdge(),
                            layer.getNumSlots(), slots));
        }
        return shelves.stream().map(shelf -> ShelfDTO.from(shelf,
                ZoneDTO.parseJson(shelf.getEdges(),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                List<com.agv.navdeployer.map.application.dto.ShelfEdgeDTO>>() {
                        }),
                layerVosByShelf.getOrDefault(shelf.getId(), List.of())))
                .toList();
    }

    public MapGridDTO getGrid(Long id) {
        MapMeta map = requireMap(id);
        if (map.getObjectKey() == null) {
            throw new IllegalStateException("地图没有栅格数据: " + map.getMapName());
        }
        try {
            byte[] json = GridObjectStore.ungzip(gridStore.get(map.getObjectKey()));
            return webMapper.readValue(json, MapGridDTO.class);
        } catch (Exception e) {
            throw new IllegalStateException("读取地图栅格失败: " + e.getMessage(), e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public MapV2DTO replaceGridFromLive(Long id, String modifiedBy) {
        MapMeta map = requireMap(id);
        MapGridDTO grid = snapshotLiveGrid();
        String objectKey = storeGrid(grid);
        String obsoleteKey = map.getObjectKey();
        map.setResolution(grid.getResolution());
        map.setWidth(grid.getWidth());
        map.setHeight(grid.getHeight());
        map.setOriginX(grid.getOriginX());
        map.setOriginY(grid.getOriginY());
        map.setOriginYaw(grid.getOriginYaw());
        map.setObjectKey(objectKey);
        map.setDataSize(null);
        map.setUpdatedAt(null);
        mapMetaMapper.updateById(map);
        if (obsoleteKey != null && !obsoleteKey.equals(objectKey)) {
            afterCommitOrNow(() -> gridStore.removeQuietly(obsoleteKey));
        }
        ticker.touch(id, modifiedBy);
        return MapV2DTO.from(map);
    }

    @Transactional(rollbackFor = Exception.class)
    public MapV2DTO createFromLive(MapCreateFromLiveCmd dto) {
        String mapName = dto.mapName().trim();
        requireMapNameAvailable(mapName);
        MapGridDTO grid = snapshotLiveGrid();
        String objectKey = storeGrid(grid);

        MapMeta map = new MapMeta();
        map.setMapName(mapName);
        map.setCoordinateSystem("map");
        map.setUnit("meters");
        map.setVersion("v0.0.1");
        map.setStatus(MapMeta.STATUS_DRAFT);
        map.setModifiedBy(dto.modifiedBy());
        map.setSource(MapMeta.SOURCE_LIVE);
        map.setResolution(grid.getResolution());
        map.setWidth(grid.getWidth());
        map.setHeight(grid.getHeight());
        map.setOriginX(grid.getOriginX());
        map.setOriginY(grid.getOriginY());
        map.setOriginYaw(grid.getOriginYaw());
        map.setObjectKey(objectKey);
        mapMetaMapper.insert(map);
        createDefaultFloor(map.getId());
        log.info("v2 map saved from live snapshot: id={}, name={}, {}x{} res={}",
                map.getId(), mapName, grid.getWidth(), grid.getHeight(), grid.getResolution());
        return MapV2DTO.from(map);
    }

    public MapGridDTO liveGrid() {
        return snapshotLiveGrid();
    }

    public MapMeta findPublished() {
        return mapMetaMapper.selectOne(Wrappers.lambdaQuery(MapMeta.class)
                .eq(MapMeta::getStatus, MapMeta.STATUS_PUBLISHED).last("LIMIT 1"));
    }

    public MapMeta getMeta(Long id) {
        return mapMetaMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public MapV2DTO createFromRobotGrid(String robotMapName, LiveMapCache.OccupancyGrid grid) {
        requireMapNameAvailable(robotMapName);
        MapGridDTO gridVO = toGridVO(grid);
        String objectKey = storeGrid(gridVO);

        MapMeta map = new MapMeta();
        map.setMapName(robotMapName);
        map.setRobotMapName(robotMapName);
        map.setCoordinateSystem("map");
        map.setUnit("meters");
        map.setVersion("v0.0.1");
        map.setStatus(MapMeta.STATUS_DRAFT);
        map.setSource(MapMeta.SOURCE_ROBOT_SYNC);
        map.setResolution(gridVO.getResolution());
        map.setWidth(gridVO.getWidth());
        map.setHeight(gridVO.getHeight());
        map.setOriginX(gridVO.getOriginX());
        map.setOriginY(gridVO.getOriginY());
        map.setOriginYaw(gridVO.getOriginYaw());
        map.setObjectKey(objectKey);
        mapMetaMapper.insert(map);
        createDefaultFloor(map.getId());
        log.info("v2 map saved from robot grid: id={}, name={}, {}x{} res={}",
                map.getId(), robotMapName, gridVO.getWidth(), gridVO.getHeight(), gridVO.getResolution());
        return MapV2DTO.from(map);
    }

    @Transactional(rollbackFor = Exception.class)
    public MapV2DTO replaceRobotGrid(Long mapId, String robotMapName, LiveMapCache.OccupancyGrid grid) {
        MapMeta map = requireMap(mapId);
        MapGridDTO gridVO = toGridVO(grid);
        String objectKey = storeGrid(gridVO);
        String obsoleteKey = map.getObjectKey();
        map.setRobotMapName(robotMapName);
        map.setResolution(gridVO.getResolution());
        map.setWidth(gridVO.getWidth());
        map.setHeight(gridVO.getHeight());
        map.setOriginX(gridVO.getOriginX());
        map.setOriginY(gridVO.getOriginY());
        map.setOriginYaw(gridVO.getOriginYaw());
        map.setObjectKey(objectKey);
        map.setDataSize(null);
        map.setUpdatedAt(null);
        mapMetaMapper.updateById(map);
        if (obsoleteKey != null && !obsoleteKey.equals(objectKey)) {
            afterCommitOrNow(() -> gridStore.removeQuietly(obsoleteKey));
        }
        ticker.touch(mapId, "map-mode-sync");
        log.info("v2 map grid replaced from robot: id={}, name={}, robotMapName={}",
                mapId, map.getMapName(), robotMapName);
        return MapV2DTO.from(mapMetaMapper.selectById(mapId));
    }

    private MapGridDTO toGridVO(LiveMapCache.OccupancyGrid grid) {
        return new MapGridDTO(grid.frameId(), grid.resolution(), grid.width(), grid.height(),
                grid.originX(), grid.originY(), grid.originYaw(), grid.data(), grid.receivedAt());
    }

    private MapGridDTO snapshotLiveGrid() {
        mapSubscriptionGuard.touch();
        LiveMapCache.OccupancyGrid grid = liveMapCache.snapshot();
        if (grid == null) {
            throw new IllegalStateException("尚未收到 /map 数据，请确认仿真栈与 rosbridge 连接正常");
        }
        return new MapGridDTO(grid.frameId(), grid.resolution(), grid.width(), grid.height(),
                grid.originX(), grid.originY(), grid.originYaw(), grid.data(), grid.receivedAt());
    }

    private String storeGrid(MapGridDTO grid) {
        try {
            byte[] json = webMapper.writeValueAsBytes(grid);
            byte[] compressed = GridObjectStore.gzip(json);
            String objectKey = "v2-maps/" + UUID.randomUUID() + "/grid.json.gz";
            gridStore.put(objectKey, compressed);
            return objectKey;
        } catch (Exception e) {
            throw new IllegalStateException("地图栅格序列化失败: " + e.getMessage(), e);
        }
    }

    private void createDefaultFloor(Long mapId) {
        MapFloor floor = new MapFloor();
        floor.setMapId(mapId);
        floor.setFloorCode("F1");
        floor.setName("Floor 1");
        floor.setElevation(0.0);
        floor.setDefaultMaxSpeed(1.0);
        floor.setSortOrder(0);
        floorMapper.insert(floor);
    }

    @Transactional(rollbackFor = Exception.class)
    public MapV2DTO update(Long id, MapUpdateCmd dto) {
        MapMeta map = requireMap(id);
        if (dto.mapName() != null && !dto.mapName().isBlank()) {
            String mapName = dto.mapName().trim();
            if (!mapName.equals(map.getMapName())) {
                requireMapNameAvailable(mapName);
            }
            map.setMapName(mapName);
        }
        if (dto.robotMapName() != null) {
            String name = dto.robotMapName().trim();
            if (!name.isEmpty() && (name.length() > 128 || name.contains("..")
                    || name.contains("/") || name.contains("\\"))) {
                throw new IllegalArgumentException("机器人地图名非法（不含路径分隔符）: '" + name + "'");
            }
            map.setRobotMapName(name.isEmpty() ? null : name);
        }
        map.setUpdatedAt(null);
        mapMetaMapper.updateById(map);
        ticker.touch(id, dto.modifiedBy());
        return MapV2DTO.from(map);
    }

    @Transactional(rollbackFor = Exception.class)
    public MapV2DTO publish(Long id, String modifiedBy) {
        MapMeta map = requireMap(id);
        if (!MapMeta.STATUS_PUBLISHED.equals(map.getStatus())) {
            mapMetaMapper.selectList(Wrappers.lambdaQuery(MapMeta.class)
                            .eq(MapMeta::getStatus, MapMeta.STATUS_PUBLISHED))
                    .forEach(published -> {
                        published.setStatus(MapMeta.STATUS_ARCHIVED);
                        published.setUpdatedAt(null);
                        mapMetaMapper.updateById(published);
                    });
            map.setStatus(MapMeta.STATUS_PUBLISHED);
            map.setUpdatedAt(null);
            mapMetaMapper.updateById(map);
        }
        ticker.touch(id, modifiedBy);
        log.info("v2 map published: id={}, name={}", id, map.getMapName());
        return MapV2DTO.from(mapMetaMapper.selectById(id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MapMeta map = requireMap(id);
        if (MapMeta.STATUS_PUBLISHED.equals(map.getStatus())) {
            throw new IllegalStateException("地图处于 PUBLISHED 状态（使用中），请先发布其他地图再删除");
        }
        mapMetaMapper.deleteById(id); // 子表经外键级联删除
        afterCommitOrNow(() -> gridStore.removeQuietly(map.getObjectKey()));
    }

    public MapMeta findByUploadName(String mapName) {
        MapMeta byRobotName = mapMetaMapper.selectOne(Wrappers.lambdaQuery(MapMeta.class)
                .eq(MapMeta::getRobotMapName, mapName).last("LIMIT 1"));
        if (byRobotName != null) {
            return byRobotName;
        }
        return mapMetaMapper.selectOne(Wrappers.lambdaQuery(MapMeta.class)
                .eq(MapMeta::getMapName, mapName).last("LIMIT 1"));
    }

    private void requireMapNameAvailable(String mapName) {
        if (mapMetaMapper.selectCount(Wrappers.lambdaQuery(MapMeta.class)
                .eq(MapMeta::getMapName, mapName)) > 0) {
            throw new IllegalStateException("地图名称已存在: " + mapName);
        }
    }

    private static void afterCommitOrNow(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}