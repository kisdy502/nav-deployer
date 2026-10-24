package com.agv.navdeployer.metadata.exchange;

import com.agv.navdeployer.metadata.MetadataJson;
import com.agv.navdeployer.metadata.dto.PathSegmentDTO;
import com.agv.navdeployer.metadata.dto.ShelfEdgeDTO;
import com.agv.navdeployer.metadata.dto.ShelfLayerDTO;
import com.agv.navdeployer.metadata.dto.ShelfSlotDTO;
import com.agv.navdeployer.metadata.entity.MapFloor;
import com.agv.navdeployer.metadata.entity.MapInterFloorConn;
import com.agv.navdeployer.metadata.entity.MapMeta;
import com.agv.navdeployer.metadata.entity.MapPath;
import com.agv.navdeployer.metadata.entity.MapPathSegment;
import com.agv.navdeployer.metadata.entity.MapShelf;
import com.agv.navdeployer.metadata.entity.MapShelfLayer;
import com.agv.navdeployer.metadata.entity.MapShelfSlot;
import com.agv.navdeployer.metadata.entity.MapWaypoint;
import com.agv.navdeployer.metadata.entity.MapZone;
import com.agv.navdeployer.metadata.exchange.format.FloorData;
import com.agv.navdeployer.metadata.exchange.format.InterFloorConnData;
import com.agv.navdeployer.metadata.exchange.format.LayerData;
import com.agv.navdeployer.metadata.exchange.format.MapInfoData;
import com.agv.navdeployer.metadata.exchange.format.MapPackageFile;
import com.agv.navdeployer.metadata.exchange.format.PathData;
import com.agv.navdeployer.metadata.exchange.format.ShelfData;
import com.agv.navdeployer.metadata.exchange.format.SlotData;
import com.agv.navdeployer.metadata.exchange.format.VersionInfoData;
import com.agv.navdeployer.metadata.exchange.format.WaypointData;
import com.agv.navdeployer.metadata.exchange.format.ZoneData;
import com.agv.navdeployer.metadata.mapper.MapFloorMapper;
import com.agv.navdeployer.metadata.mapper.MapInterFloorConnMapper;
import com.agv.navdeployer.metadata.mapper.MapMetaMapper;
import com.agv.navdeployer.metadata.mapper.MapPathMapper;
import com.agv.navdeployer.metadata.mapper.MapPathSegmentMapper;
import com.agv.navdeployer.metadata.mapper.MapShelfLayerMapper;
import com.agv.navdeployer.metadata.mapper.MapShelfMapper;
import com.agv.navdeployer.metadata.mapper.MapShelfSlotMapper;
import com.agv.navdeployer.metadata.mapper.MapWaypointMapper;
import com.agv.navdeployer.metadata.mapper.MapZoneMapper;
import com.agv.navdeployer.metadata.service.GridObjectStore;
import com.agv.navdeployer.metadata.service.MapMetaService;
import com.agv.navdeployer.metadata.service.MapZoneService;
import com.agv.navdeployer.metadata.vo.ConnVO;
import com.agv.navdeployer.metadata.vo.FloorBundleVO;
import com.agv.navdeployer.metadata.vo.MapFullVO;
import com.agv.navdeployer.metadata.vo.MapV2VO;
import com.agv.navdeployer.metadata.vo.PathV2VO;
import com.agv.navdeployer.metadata.vo.SegmentVO;
import com.agv.navdeployer.vo.MapGridVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 新格式地图包（核心四件套，命名对齐真机 shenzhen_v7.zip）：
 *
 * <pre>
 *   map_info.json      版本信息 + 地图元数据（楼层/点位/区域/货架/路线/楼层间连接，camelCase）
 *   global_map.pgm     栅格（P5：254 空/0 占据/205 未知）
 *   global_map.png     同尺寸灰度预览
 *   global_map.yaml    栅格元数据（map_server 风格 + mode/width/height）
 *   global_map.yaml.lxmap 旧调度拓扑兼容文件
 * </pre>
 *
 * <p>真机包内的 global_map.json / global_map.feature.json / virtual_walls.json
 * 不生成；额外生成 global_map.yaml.lxmap 兼容旧调度服务器。
 */
@Component
public class MapPackageService {

    private static final Logger log = LoggerFactory.getLogger(MapPackageService.class);

    public static final String ENTRY_MAP_INFO = "map_info.json";
    public static final String ENTRY_YAML = "global_map.yaml";
    public static final String ENTRY_PGM = "global_map.pgm";
    public static final String ENTRY_PNG = "global_map.png";
    public static final String ENTRY_LXMAP = "global_map.yaml.lxmap";

    private static final DateTimeFormatter LAST_MODIFIED_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final Set<String> WAYPOINT_TYPES = Set.of(
            "path", "charge", "pickup", "dropoff", "storage", "elevator", "stairs",
            "ramp", "relocation", "parking", "workstation", "dock", "roller");
    private static final Set<String> ZONE_TYPES = Set.of("forbidden", "slowDown", "ramp");
    private static final Set<String> CONN_TYPES = Set.of("elevator", "stairs", "ramp");

    private final MapMetaService mapMetaService;
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
    private final GridObjectStore gridStore;
    private final com.fasterxml.jackson.databind.ObjectMapper webMapper;

    public MapPackageService(MapMetaService mapMetaService,
                             MapMetaMapper mapMetaMapper,
                             MapFloorMapper floorMapper,
                             MapWaypointMapper waypointMapper,
                             MapZoneMapper zoneMapper,
                             MapShelfMapper shelfMapper,
                             MapShelfLayerMapper shelfLayerMapper,
                             MapShelfSlotMapper shelfSlotMapper,
                             MapPathMapper pathMapper,
                             MapPathSegmentMapper pathSegmentMapper,
                             MapInterFloorConnMapper connMapper,
                             GridObjectStore gridStore,
                             com.fasterxml.jackson.databind.ObjectMapper webMapper) {
        this.mapMetaService = mapMetaService;
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
        this.gridStore = gridStore;
        this.webMapper = webMapper;
    }

    // ==================== 导出：DB → 新格式 zip ====================

    public byte[] exportZip(Long mapId) {
        try {
            MapFullVO full = mapMetaService.loadFull(mapId);
            MapMeta map = mapMetaService.requireMap(mapId);
            MapGridVO grid = mapMetaService.getGrid(mapId);

            MapPackageFile file = buildPackageFile(map, full);
            byte[] mapInfoJson = MetadataJson.MAPPER
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(file);
            byte[] yaml = buildYaml(grid);
            byte[] pgm = PgmImageUtil.buildPgm(grid.getWidth(), grid.getHeight(), grid.getData());
            byte[] png = PgmImageUtil.buildPng(grid.getWidth(), grid.getHeight(), grid.getData());

            // 完整 map_info.json 落日志：RMS 侧解析失败时对照内容排查（类型词表/字段缺失等）
            log.info("map package {} ({} bytes):\n{}",
                    ENTRY_MAP_INFO, mapInfoJson.length, new String(mapInfoJson, StandardCharsets.UTF_8));

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                putEntry(zip, ENTRY_MAP_INFO, mapInfoJson);
                putEntry(zip, ENTRY_PGM, pgm);
                putEntry(zip, ENTRY_PNG, png);
                putEntry(zip, ENTRY_YAML, yaml);
                putEntry(zip, ENTRY_LXMAP, LegacyTopologyExporter.export(grid, full));
            }
            int floorCount = full.floors().size();
            log.info("v2 map package exported: id={}, name={}, floors={}, {} bytes",
                    mapId, map.getMapName(), floorCount, buffer.size());
            // 以包内实际条目为准再打一遍（反读 zip，暴露任何命名/路径前缀问题）
            logZipEntries(buffer.toByteArray());
            return buffer.toByteArray();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("地图包导出失败: " + e.getMessage(), e);
        }
    }

    /** 反读 zip 打印每个条目的真实文件名与大小（排查 RMS 侧按名取文件失败）。 */
    private void logZipEntries(byte[] zipBytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            StringBuilder names = new StringBuilder();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(entry.getName()).append('(').append(entry.getSize()).append('B');
                    if (entry.getSize() < 0) {
                        names.append(", compressed=").append(entry.getCompressedSize()).append('B');
                    }
                    names.append(')');
                }
            }
            log.info("map package zip entries: [{}]", names);
        } catch (Exception e) {
            log.warn("map package zip entries read failed: {}", e.getMessage());
        }
    }

    private MapPackageFile buildPackageFile(MapMeta map, MapFullVO full) {
        // 点位 id → code（路线 from/to/waypoints 输出编码）
        Map<Long, String> waypointCodes = new HashMap<>();
        full.floors().forEach(bundle -> bundle.waypoints()
                .forEach(waypoint -> waypointCodes.put(waypoint.id(), waypoint.waypointCode())));

        List<FloorData> floors = full.floors().stream()
                .map(bundle -> new FloorData(
                        bundle.floor().floorCode(),
                        bundle.floor().name(),
                        bundle.floor().elevation(),
                        bundle.floor().defaultMaxSpeed(),
                        bundle.waypoints().stream()
                                .map(waypoint -> new WaypointData(
                                        waypoint.waypointCode(), waypoint.name(),
                                        new double[]{waypoint.x(), waypoint.y(), waypoint.yaw()},
                                        waypoint.wpType()))
                                .toList(),
                        bundle.zones().stream()
                                .map(zone -> new ZoneData(
                                        zone.zoneCode(), zone.name(), zone.zoneType(),
                                        zone.priority() != null ? zone.priority() : 2,
                                        zone.maxSpeed() != null ? zone.maxSpeed() : 0.0,
                                        zone.shape() != null ? zone.shape() : "polygon",
                                        zone.height(),
                                        zone.vertices() == null ? List.of() : zone.vertices(),
                                        zone.rampInfo() == null ? null : new ZoneData.RampInfo(
                                                zone.rampInfo().slope(), zone.rampInfo().direction(),
                                                zone.rampInfo().startElevation(),
                                                zone.rampInfo().endElevation()),
                                        zone.description()))
                                .toList(),
                        bundle.shelves().stream()
                                .map(shelf -> new ShelfData(
                                        shelf.shelfCode(), shelf.name(),
                                        new com.agv.navdeployer.metadata.exchange.format.FootprintData(
                                                "polygon",
                                                shelf.vertices() == null ? List.of() : shelf.vertices(),
                                                shelf.height() != null ? shelf.height() : 1.8,
                                                shelf.edges() == null ? List.of() : shelf.edges().stream()
                                                        .map(edge -> new com.agv.navdeployer.metadata.exchange.format.FootprintData.Edge(
                                                                edge.edgeIndex(), edge.vertexStart(),
                                                                edge.vertexEnd(), edge.direction()))
                                                        .toList()),
                                        shelf.layers() == null ? List.of() : shelf.layers().stream()
                                                .map(layer -> new LayerData(
                                                        layer.layerIndex(), layer.elevation(),
                                                        layer.accessEdge() != null ? layer.accessEdge() : 0,
                                                        layer.numSlots() != null ? layer.numSlots() : 0,
                                                        layer.slots() == null ? List.of() : layer.slots().stream()
                                                                .map(slot -> new SlotData(
                                                                        slot.slotIndex(), slot.position(),
                                                                        slot.size(),
                                                                        slot.productId() == null && slot.productName() == null
                                                                                ? null
                                                                                : new SlotData.Product(
                                                                                slot.productId(), slot.productName())))
                                                                .toList()))
                                                .toList()))
                                .toList(),
                        bundle.paths().stream()
                                .map(path -> toPathData(path, waypointCodes))
                                .toList()))
                .toList();

        List<InterFloorConnData> conns = full.connections().stream()
                .map(conn -> new InterFloorConnData(
                        conn.connCode(), conn.connType(), conn.name(),
                        conn.connections() == null ? List.of() : conn.connections().stream()
                                .map(endpoint -> new InterFloorConnData.Endpoint(
                                        endpoint.floorId(), endpoint.waypointId()))
                                .toList(),
                        conn.capacity(), conn.maxSpeed(),
                        conn.rampInfo() == null ? null : new InterFloorConnData.RampMeta(
                                conn.rampInfo().slope(), conn.rampInfo().length(),
                                conn.rampInfo().surface())))
                .toList();

        VersionInfoData versionInfo = new VersionInfoData(
                map.getVersion(),
                MapMeta.STATUS_PUBLISHED.equals(map.getStatus()) ? "published" : "",
                map.getUpdatedAt() != null
                        ? LAST_MODIFIED_FORMAT.format(map.getUpdatedAt().atZone(ZoneOffset.systemDefault()))
                        : null,
                map.getModifiedBy());
        MapInfoData mapInfo = new MapInfoData(map.getMapName(),
                map.getCoordinateSystem() != null ? map.getCoordinateSystem() : "map",
                map.getUnit() != null ? map.getUnit() : "meters",
                floors, conns);
        return new MapPackageFile(versionInfo, mapInfo);
    }

    /** 路线 → PathData；段扩展仅在存在曲线/段级差异时携带。 */
    private static PathData toPathData(PathV2VO path, Map<Long, String> waypointCodes) {
        List<String> waypoints = path.waypointIds().stream()
                .map(id -> waypointCodes.getOrDefault(id, String.valueOf(id)))
                .toList();
        List<PathData.Segment> segments = null;
        boolean hasExtension = path.segments() != null && path.segments().stream().anyMatch(segment ->
                MapPathSegment.TYPE_CURVE.equals(segment.segmentType())
                        || (segment.controlPoints() != null && !segment.controlPoints().isEmpty())
                        || segment.maxSpeed() != null
                        || Boolean.TRUE.equals(segment.backUp())
                        || Boolean.TRUE.equals(segment.reverse()));
        if (hasExtension) {
            segments = path.segments().stream()
                    .map(segment -> new PathData.Segment(
                            segment.seq(),
                            waypointCodes.getOrDefault(segment.sourceWaypointId(),
                                    String.valueOf(segment.sourceWaypointId())),
                            waypointCodes.getOrDefault(segment.targetWaypointId(),
                                    String.valueOf(segment.targetWaypointId())),
                            segment.segmentType(),
                            segment.controlPoints(),
                            segment.maxSpeed(), segment.backUp(), segment.reverse()))
                    .toList();
        }
        return new PathData(
                path.pathCode(),
                waypointCodes.getOrDefault(path.fromWaypointId(), String.valueOf(path.fromWaypointId())),
                waypointCodes.getOrDefault(path.toWaypointId(), String.valueOf(path.toWaypointId())),
                waypoints,
                path.isBidirectional() == null || path.isBidirectional(),
                path.maxSpeed(),
                path.enableObstacleAvoidance() == null || path.enableObstacleAvoidance(),
                segments);
    }

    /** 栅格 yaml（对齐真机 global_map.yaml：image/mode/resolution/width/height/origin/negate/thresholds）。 */
    private static byte[] buildYaml(MapGridVO grid) {
        String yaml = "# v2 map package metadata\n"
                + "image: " + ENTRY_PGM + "\n"
                + "mode: trinary\n"
                + "resolution: " + grid.getResolution() + "\n"
                + "width: " + grid.getWidth() + "\n"
                + "height: " + grid.getHeight() + "\n"
                + "origin: [" + grid.getOriginX() + ", " + grid.getOriginY() + ", "
                + grid.getOriginYaw() + "]\n"
                + "negate: 0\n"
                + "occupied_thresh: 0.65\n"
                + "free_thresh: 0.25\n";
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 导入：新格式 zip → DB ====================

    @Transactional(rollbackFor = Exception.class)
    public MapV2VO importZip(byte[] zipBytes, String nameOverride, String modifiedBy) {
        try {
            Map<String, byte[]> entries = readZipEntries(zipBytes);
            // 兼容新旧命名：map_info.json（真机）/ map-info.json（早期导出）；pgm 按后缀兜底
            byte[] mapInfoBytes = firstEntry(entries, ENTRY_MAP_INFO, "map-info.json");
            byte[] pgmBytes = firstEntry(entries, ENTRY_PGM, "global.pgm");
            if (pgmBytes == null) {
                pgmBytes = entries.entrySet().stream()
                        .filter(e -> e.getKey().toLowerCase().endsWith(".pgm"))
                        .findFirst().map(Map.Entry::getValue).orElse(null);
            }
            if (mapInfoBytes == null || pgmBytes == null) {
                throw new IllegalArgumentException("zip 缺少必需文件（" + ENTRY_MAP_INFO + " / "
                        + ENTRY_PGM + "），现有条目: " + entries.keySet());
            }
            byte[] yamlBytes = firstEntry(entries, ENTRY_YAML, "global.yaml");
            MapPackageFile file = MetadataJson.MAPPER.readValue(mapInfoBytes, MapPackageFile.class);
            if (file == null || file.mapInfo() == null || file.mapInfo().mapName() == null) {
                throw new IllegalArgumentException(ENTRY_MAP_INFO + " 缺少 mapInfo.mapName");
            }
            YamlMeta yaml = parseYaml(yamlBytes == null ? null
                    : new String(yamlBytes, StandardCharsets.UTF_8));
            PgmImageUtil.ParsedPgm pgm = PgmImageUtil.parsePgm(pgmBytes);

            String mapName = nameOverride != null && !nameOverride.isBlank()
                    ? nameOverride.trim() : file.mapInfo().mapName().trim();
            if (mapMetaMapper.selectCount(Wrappers.lambdaQuery(MapMeta.class)
                    .eq(MapMeta::getMapName, mapName)) > 0) {
                throw new IllegalStateException("地图名称已存在: " + mapName + "（可换名导入）");
            }

            // 地图头 + 栅格
            MapMeta map = new MapMeta();
            map.setMapName(mapName);
            map.setCoordinateSystem(file.mapInfo().coordinateSystem() != null
                    ? file.mapInfo().coordinateSystem() : "map");
            map.setUnit(file.mapInfo().unit() != null ? file.mapInfo().unit() : "meters");
            map.setVersion(file.versionInfo() != null && file.versionInfo().version() != null
                    && !file.versionInfo().version().isBlank()
                    ? file.versionInfo().version() : "v0.0.1");
            map.setStatus(MapMeta.STATUS_DRAFT);
            map.setModifiedBy(modifiedBy != null ? modifiedBy
                    : file.versionInfo() != null ? file.versionInfo().modifiedBy() : null);
            map.setSource(MapMeta.SOURCE_IMPORT);
            map.setResolution(yaml.resolution());
            map.setWidth(pgm.width());
            map.setHeight(pgm.height());
            map.setOriginX(yaml.originX());
            map.setOriginY(yaml.originY());
            map.setOriginYaw(yaml.originYaw());
            MapGridVO grid = new MapGridVO("map", yaml.resolution(), pgm.width(), pgm.height(),
                    yaml.originX(), yaml.originY(), yaml.originYaw(), pgm.data(), java.time.Instant.now());
            byte[] compressed = GridObjectStore.gzip(webMapper.writeValueAsBytes(grid));
            String objectKey = "v2-maps/" + java.util.UUID.randomUUID() + "/grid.json.gz";
            gridStore.put(objectKey, compressed);
            map.setObjectKey(objectKey);
            map.setDataSize((long) compressed.length);
            mapMetaMapper.insert(map);

            importTree(map, file.mapInfo());
            log.info("v2 map package imported: id={}, name={}, floors={}",
                    map.getId(), mapName, file.mapInfo().floors() == null ? 0 : file.mapInfo().floors().size());
            return MapV2VO.from(map);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("地图包导入失败: " + e.getMessage(), e);
        }
    }

    private void importTree(MapMeta map, MapInfoData mapInfo) {
        List<FloorData> floors = mapInfo.floors() == null ? List.of() : mapInfo.floors();
        Map<String, Long> waypointIdByCode = new LinkedHashMap<>();
        Map<String, Long> floorIdByCode = new LinkedHashMap<>();
        Set<String> seenFloorCodes = new HashSet<>();
        Set<String> seenWaypointCodes = new HashSet<>();
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            FloorData floorData = floors.get(floorIndex);
            String floorCode = floorData.floorId() == null || floorData.floorId().isBlank()
                    ? "F" + (floorIndex + 1) : floorData.floorId().trim();
            if (!seenFloorCodes.add(floorCode)) {
                throw new IllegalArgumentException("楼层标识重复: " + floorCode);
            }
            MapFloor floor = new MapFloor();
            floor.setMapId(map.getId());
            floor.setFloorCode(floorCode);
            floor.setName(floorData.name() == null || floorData.name().isBlank()
                    ? floorCode : floorData.name());
            floor.setElevation(floorData.elevation());
            floor.setDefaultMaxSpeed(floorData.defaultMaxSpeed());
            floor.setSortOrder(floorIndex);
            floorMapper.insert(floor);
            floorIdByCode.put(floorCode, floor.getId());

            // 点位
            List<WaypointData> waypoints = floorData.waypoints() == null ? List.of() : floorData.waypoints();
            for (WaypointData waypointData : waypoints) {
                String code = waypointData.id();
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("点位缺少 id（楼层 " + floorCode + "）");
                }
                if (!seenWaypointCodes.add(code)) {
                    throw new IllegalArgumentException("点位编码重复: " + code);
                }
                MapWaypoint waypoint = new MapWaypoint();
                waypoint.setMapId(map.getId());
                waypoint.setFloorId(floor.getId());
                waypoint.setWaypointCode(code);
                waypoint.setName(waypointData.name() == null || waypointData.name().isBlank()
                        ? code : waypointData.name());
                waypoint.setWpType(normalizeWaypointType(waypointData.type()));
                double[] position = waypointData.position();
                if (position == null || position.length < 2) {
                    throw new IllegalArgumentException("点位 " + code + " 缺少 position [x,y,theta]");
                }
                waypoint.setX(position[0]);
                waypoint.setY(position[1]);
                waypoint.setYaw(position.length > 2 ? position[2] : 0.0);
                waypointMapper.insert(waypoint);
                waypointIdByCode.put(code, waypoint.getId());
            }

            // 区域
            List<ZoneData> zones = floorData.zoneConstraints() == null ? List.of() : floorData.zoneConstraints();
            for (ZoneData zoneData : zones) {
                String type = normalizeZoneType(zoneData.type());
                MapZone zone = new MapZone();
                zone.setMapId(map.getId());
                zone.setFloorId(floor.getId());
                zone.setZoneCode(requireCode(zoneData.id(), "区域"));
                zone.setName(zoneData.name() == null || zoneData.name().isBlank()
                        ? zoneData.id() : zoneData.name());
                zone.setZoneType(type);
                zone.setPriority(zoneData.priority());
                zone.setMaxSpeed(type.equals("forbidden") ? 0.0 : zoneData.maxSpeed());
                zone.setShape(zoneData.shape() == null ? "polygon" : zoneData.shape());
                zone.setVertices(MapZoneService.toJson(zoneData.vertices() == null
                        ? List.of() : zoneData.vertices()));
                zone.setHeight(zoneData.height());
                zone.setRampInfo(zoneData.rampInfo() == null ? null : MapZoneService.toJson(
                        new com.agv.navdeployer.metadata.dto.RampInfoDTO(
                                zoneData.rampInfo().slope(), zoneData.rampInfo().direction(),
                                zoneData.rampInfo().startElevation(), zoneData.rampInfo().endElevation())));
                zone.setDescription(zoneData.description());
                zoneMapper.insert(zone);
            }

            // 货架（含层/格位）
            List<ShelfData> shelves = floorData.shelves() == null ? List.of() : floorData.shelves();
            for (ShelfData shelfData : shelves) {
                MapShelf shelf = new MapShelf();
                shelf.setMapId(map.getId());
                shelf.setFloorId(floor.getId());
                shelf.setShelfCode(requireCode(shelfData.id(), "货架"));
                shelf.setName(shelfData.name() == null || shelfData.name().isBlank()
                        ? shelfData.id() : shelfData.name());
                boolean hasFootprint = shelfData.footprint() != null
                        && shelfData.footprint().vertices() != null;
                shelf.setVertices(MapZoneService.toJson(hasFootprint
                        ? shelfData.footprint().vertices() : List.of()));
                shelf.setHeight(shelfData.footprint() != null && shelfData.footprint().height() > 0
                        ? shelfData.footprint().height() : 1.8);
                List<ShelfEdgeDTO> edges = shelfData.footprint() == null
                        || shelfData.footprint().edges() == null ? List.of()
                        : shelfData.footprint().edges().stream()
                        .map(edge -> new ShelfEdgeDTO(edge.edgeIndex(), edge.vertexStart(),
                                edge.vertexEnd(), edge.direction()))
                        .toList();
                shelf.setEdges(MapZoneService.toJson(edges));
                shelfMapper.insert(shelf);
                List<LayerData> layers = shelfData.layers() == null ? List.of() : shelfData.layers();
                for (int i = 0; i < layers.size(); i++) {
                    LayerData layerData = layers.get(i);
                    MapShelfLayer layer = new MapShelfLayer();
                    layer.setShelfId(shelf.getId());
                    layer.setLayerIndex(layerData.layerIndex());
                    layer.setElevation(layerData.elevation());
                    layer.setAccessEdge(layerData.accessEdge());
                    List<SlotData> slots = layerData.slots() == null ? List.of() : layerData.slots();
                    layer.setNumSlots(layerData.numSlots() > 0 ? layerData.numSlots() : slots.size());
                    shelfLayerMapper.insert(layer);
                    for (SlotData slotData : slots) {
                        MapShelfSlot slot = new MapShelfSlot();
                        slot.setLayerId(layer.getId());
                        slot.setShelfId(shelf.getId());
                        slot.setSlotIndex(slotData.slotIndex());
                        double[] position = slotData.position();
                        if (position == null || position.length < 3) {
                            throw new IllegalArgumentException("货架 " + shelfData.id()
                                    + " 格位缺少 position [x,y,z]");
                        }
                        slot.setX(position[0]);
                        slot.setY(position[1]);
                        slot.setZ(position[2]);
                        double[] size = slotData.size() != null && slotData.size().length >= 3
                                ? slotData.size() : new double[]{0.4, 0.6, 0.3};
                        slot.setWidth(size[0]);
                        slot.setDepth(size[1]);
                        slot.setHeight(size[2]);
                        if (slotData.product() != null) {
                            slot.setProductId(slotData.product().id());
                            slot.setProductName(slotData.product().name());
                        }
                        shelfSlotMapper.insert(slot);
                    }
                }
            }

            // 路线（含段扩展）
            List<PathData> paths = floorData.paths() == null ? List.of() : floorData.paths();
            for (PathData pathData : paths) {
                List<String> codes = pathData.waypoints() == null ? List.of() : pathData.waypoints();
                if (codes.size() < 2) {
                    throw new IllegalArgumentException("路线 " + pathData.id() + " 至少需要 2 个途经点位");
                }
                List<Long> chain = new ArrayList<>(codes.size());
                for (String code : codes) {
                    Long waypointId = waypointIdByCode.get(code);
                    if (waypointId == null) {
                        throw new IllegalArgumentException("路线 " + pathData.id() + " 引用了不存在的点位: " + code);
                    }
                    chain.add(waypointId);
                }
                MapPath path = new MapPath();
                path.setMapId(map.getId());
                path.setFloorId(floor.getId());
                path.setPathCode(requireCode(pathData.id(), "路线"));
                path.setName(pathData.id());
                path.setFromWaypointId(chain.get(0));
                path.setToWaypointId(chain.get(chain.size() - 1));
                path.setIsBidirectional(pathData.isBidirectional());
                path.setMaxSpeed(pathData.maxSpeed());
                path.setEnableObstacleAvoidance(pathData.enableObstacleAvoidance() == null
                        || pathData.enableObstacleAvoidance());
                path.setStatus(MapPath.STATUS_DRAFT);
                pathMapper.insert(path);
                List<PathData.Segment> segments = pathData.segments();
                int seq = 1;
                for (int i = 0; i + 1 < chain.size(); i++) {
                    int segIndex = seq - 1;
                    MapPathSegment segment = new MapPathSegment();
                    segment.setPathId(path.getId());
                    segment.setSeq(seq++);
                    segment.setSourceWaypointId(chain.get(i));
                    segment.setTargetWaypointId(chain.get(i + 1));
                    PathData.Segment extension = segments != null && segIndex < segments.size()
                            ? segments.get(segIndex) : null;
                    if (extension != null && "CURVE".equalsIgnoreCase(extension.segmentType())) {
                        segment.setSegmentType(MapPathSegment.TYPE_CURVE);
                        segment.setControlPoints(extension.controlPoints() == null
                                ? null : MapZoneService.toJson(extension.controlPoints()));
                        if (segment.getControlPoints() == null) {
                            throw new IllegalArgumentException("路线 " + pathData.id() + " 第 "
                                    + (seq - 1) + " 段为 CURVE 但缺少控制点");
                        }
                    } else {
                        segment.setSegmentType(MapPathSegment.TYPE_STRAIGHT);
                    }
                    if (extension != null) {
                        segment.setMaxSpeed(extension.maxSpeed());
                        segment.setBackUp(Boolean.TRUE.equals(extension.backUp()));
                        segment.setReverse(Boolean.TRUE.equals(extension.reverse()));
                    }
                    pathSegmentMapper.insert(segment);
                }
            }
        }

        // 楼层间连接
        List<InterFloorConnData> conns = mapInfo.interFloorConnections() == null
                ? List.of() : mapInfo.interFloorConnections();
        for (InterFloorConnData connData : conns) {
            MapInterFloorConn conn = new MapInterFloorConn();
            conn.setMapId(map.getId());
            conn.setConnCode(requireCode(connData.id(), "楼层间连接"));
            conn.setName(connData.name() == null || connData.name().isBlank()
                    ? connData.id() : connData.name());
            conn.setConnType(normalizeConnType(connData.type()));
            List<InterFloorConnData.Endpoint> endpoints = connData.connections() == null
                    ? List.of() : connData.connections();
            for (InterFloorConnData.Endpoint endpoint : endpoints) {
                if (!floorIdByCode.containsKey(endpoint.floorId())) {
                    throw new IllegalArgumentException("连接 " + connData.id()
                            + " 引用了不存在的楼层: " + endpoint.floorId());
                }
                if (!waypointIdByCode.containsKey(endpoint.waypointId())) {
                    throw new IllegalArgumentException("连接 " + connData.id()
                            + " 引用了不存在的点位: " + endpoint.waypointId());
                }
            }
            conn.setConnections(MapZoneService.toJson(endpoints));
            conn.setCapacity(connData.capacity());
            conn.setMaxSpeed(connData.maxSpeed());
            conn.setRampInfo(connData.rampInfo() == null ? null : MapZoneService.toJson(
                    new com.agv.navdeployer.metadata.dto.ConnRampInfoDTO(
                            connData.rampInfo().slope(), connData.rampInfo().length(),
                            connData.rampInfo().surface())));
            connMapper.insert(conn);
        }
    }

    private static String normalizeWaypointType(String type) {
        if (type == null || type.isBlank()) {
            return "path";
        }
        // 旧词表归一（normal → path）
        if ("normal".equalsIgnoreCase(type.trim())) {
            return "path";
        }
        for (String supported : WAYPOINT_TYPES) {
            if (supported.equalsIgnoreCase(type.trim())) {
                return supported;
            }
        }
        return "path";
    }

    private static String normalizeZoneType(String type) {
        if (type != null && ("slow_down".equalsIgnoreCase(type.trim())
                || "slowdown".equalsIgnoreCase(type.trim()))) {
            return "slowDown";
        }
        if (type != null) {
            for (String supported : ZONE_TYPES) {
                if (supported.equalsIgnoreCase(type.trim())) {
                    return supported;
                }
            }
        }
        throw new IllegalArgumentException("不支持的区域类型: " + type + "（可选 forbidden/slowDown/ramp）");
    }

    private static String normalizeConnType(String type) {
        if (type != null) {
            for (String supported : CONN_TYPES) {
                if (supported.equalsIgnoreCase(type.trim())) {
                    return supported;
                }
            }
        }
        throw new IllegalArgumentException("不支持的连接类型: " + type + "（可选 elevator/stairs/ramp）");
    }

    private static String requireCode(String code, String what) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException(what + "缺少 id");
        }
        return code.trim();
    }

    private record YamlMeta(double resolution, double originX, double originY, double originYaw) {
    }

    private static YamlMeta parseYaml(String yaml) {
        double resolution = 0.05;
        double originX = 0;
        double originY = 0;
        double originYaw = 0;
        if (yaml != null) {
            for (String line : yaml.split("\\R")) {
                String trimmed = line.split("#", 2)[0].trim();
                if (trimmed.startsWith("resolution:")) {
                    resolution = Double.parseDouble(trimmed.substring("resolution:".length()).trim());
                } else if (trimmed.startsWith("origin:")) {
                    String body = trimmed.substring(trimmed.indexOf('[') + 1, trimmed.lastIndexOf(']'));
                    String[] parts = body.split(",");
                    originX = Double.parseDouble(parts[0].trim());
                    originY = Double.parseDouble(parts[1].trim());
                    if (parts.length > 2) {
                        originYaw = Double.parseDouble(parts[2].trim());
                    }
                }
            }
        }
        return new YamlMeta(resolution, originX, originY, originYaw);
    }

    private static Map<String, byte[]> readZipEntries(byte[] zipBytes) throws java.io.IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] content) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** 按候选名顺序取第一个存在的条目。 */
    private static byte[] firstEntry(Map<String, byte[]> entries, String... names) {
        for (String name : names) {
            byte[] content = entries.get(name);
            if (content != null) {
                return content;
            }
        }
        return null;
    }
}
