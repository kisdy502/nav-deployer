package com.agv.navdeployer.service;

import com.agv.navdeployer.entity.NavMap;
import com.agv.navdeployer.entity.NavPoint;
import com.agv.navdeployer.entity.NavPath;
import com.agv.navdeployer.exception.NotFoundException;
import com.agv.navdeployer.mapper.NavMapMapper;
import com.agv.navdeployer.mapper.NavPointMapper;
import com.agv.navdeployer.mapper.NavPathMapper;
import com.agv.navdeployer.sim.LiveMapCache;
import com.agv.navdeployer.vo.MapGridVO;
import com.agv.navdeployer.vo.NavMapVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 地图管理：实时栅格快照建图（栅格 gzip 存 MinIO）、列表/详情、激活为当前部署地图。
 */
@Service
public class NavMapService {

    private static final Logger log = LoggerFactory.getLogger(NavMapService.class);

    private final NavMapMapper navMapMapper;
    private final NavPointMapper navPointMapper;
    private final NavPathMapper navPathMapper;
    private final MinioClient minioClient;
    private final LiveMapCache liveMapCache;
    private final ObjectMapper objectMapper;
    private final String mapsBucket;

    public NavMapService(NavMapMapper navMapMapper,
                         NavPointMapper navPointMapper,
                         NavPathMapper navPathMapper,
                         MinioClient minioClient,
                         LiveMapCache liveMapCache,
                         ObjectMapper objectMapper,
                         @Value("${minio.maps-bucket:nav-maps}") String mapsBucket) {
        this.navMapMapper = navMapMapper;
        this.navPointMapper = navPointMapper;
        this.navPathMapper = navPathMapper;
        this.minioClient = minioClient;
        this.liveMapCache = liveMapCache;
        this.objectMapper = objectMapper;
        this.mapsBucket = mapsBucket;
    }

    /** 实时栅格（/map 最新一帧）。 */
    public MapGridVO liveGrid() {
        LiveMapCache.OccupancyGrid grid = liveMapCache.snapshot();
        if (grid == null) {
            throw new IllegalStateException("尚未收到 /map 数据，请确认仿真栈与 rosbridge 连接正常");
        }
        return toGridVO(grid);
    }

    /** 从实时快照建图：栅格序列化（gzip）入 MinIO，元数据入库。 */
    @Transactional(rollbackFor = Exception.class)
    public NavMapVO createFromLive(String mapName) {
        LiveMapCache.OccupancyGrid grid = liveMapCache.snapshot();
        if (grid == null) {
            throw new IllegalStateException("尚未收到 /map 数据，请确认仿真栈与 rosbridge 连接正常");
        }
        requireMapNameAvailable(mapName);

        byte[] compressed;
        try {
            byte[] json = objectMapper.writeValueAsBytes(toGridVO(grid));
            compressed = gzip(json);
        } catch (IOException e) {
            throw new IllegalStateException("地图数据序列化失败: " + e.getMessage(), e);
        }
        String objectKey = "nav-maps/" + UUID.randomUUID() + "/grid.json.gz";
        putMapObject(objectKey, compressed);

        NavMap map = new NavMap();
        map.setMapName(mapName);
        map.setStatus(NavMap.STATUS_DRAFT);
        map.setSource("LIVE");
        map.setResolution(grid.resolution());
        map.setWidth(grid.width());
        map.setHeight(grid.height());
        map.setOriginX(grid.originX());
        map.setOriginY(grid.originY());
        map.setOriginYaw(grid.originYaw());
        map.setObjectKey(objectKey);
        map.setDataSize((long) compressed.length);
        navMapMapper.insert(map);
        log.info("map saved from live snapshot: id={}, name={}, {}x{} res={} gz={}B",
                map.getId(), mapName, grid.width(), grid.height(), grid.resolution(), compressed.length);
        return NavMapVO.from(map);
    }

    /**
     * 机器人侧地图同步入库（save_map 成功后由 MapModeTaskService 调用）。
     * upsert 语义：同名地图覆盖栅格与机器人关联（source=ROBOT_SYNC），否则新建 DRAFT。
     */
    @Transactional(rollbackFor = Exception.class)
    public NavMapVO saveRobotGrid(String mapName, String robotMapName, LiveMapCache.OccupancyGrid grid) {
        byte[] compressed;
        try {
            byte[] json = objectMapper.writeValueAsBytes(toGridVO(grid));
            compressed = gzip(json);
        } catch (IOException e) {
            throw new IllegalStateException("地图数据序列化失败: " + e.getMessage(), e);
        }
        String objectKey = "nav-maps/" + UUID.randomUUID() + "/grid.json.gz";
        putMapObject(objectKey, compressed);

        NavMap map = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getMapName, mapName).last("LIMIT 1"));
        String oldObjectKey = null;
        if (map == null) {
            map = new NavMap();
            map.setMapName(mapName);
            map.setStatus(NavMap.STATUS_DRAFT);
            map.setSource("ROBOT_SYNC");
        } else {
            oldObjectKey = map.getObjectKey();
        }
        map.setRobotMapName(robotMapName);
        map.setResolution(grid.resolution());
        map.setWidth(grid.width());
        map.setHeight(grid.height());
        map.setOriginX(grid.originX());
        map.setOriginY(grid.originY());
        map.setOriginYaw(grid.originYaw());
        map.setObjectKey(objectKey);
        map.setDataSize((long) compressed.length);
        map.setUpdatedAt(null);
        if (map.getId() == null) {
            navMapMapper.insert(map);
        } else {
            navMapMapper.updateById(map);
        }
        String obsoleteKey = oldObjectKey;
        if (obsoleteKey != null && !obsoleteKey.equals(objectKey)) {
            afterCommitOrNow(() -> removeObjectQuietly(obsoleteKey));
        }
        log.info("map synced from robot: id={}, name={}, robotMapName={}, {}x{} gz={}B",
                map.getId(), mapName, robotMapName, grid.width(), grid.height(), compressed.length);
        return NavMapVO.from(map);
    }

    public List<NavMapVO> list() {
        return navMapMapper.selectList(Wrappers.lambdaQuery(NavMap.class).orderByDesc(NavMap::getId))
                .stream().map(NavMapVO::from).toList();
    }

    public NavMap getById(Long id) {
        return findOrThrow(id);
    }

    public NavMapVO get(Long id) {
        return NavMapVO.from(findOrThrow(id));
    }

    /** 已存地图的栅格数据（解压后的 JSON 字节，application/json）。 */
    public StoredGrid getGridData(Long id) {
        NavMap map = findOrThrow(id);
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder().bucket(mapsBucket).object(map.getObjectKey()).build())) {
            return new StoredGrid(ungzip(response.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("从 MinIO 读取地图数据失败: " + e.getMessage(), e);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public NavMapVO rename(Long id, String mapName) {
        requireMapNameAvailable(mapName);
        NavMap map = findOrThrow(id);
        map.setMapName(mapName);
        map.setUpdatedAt(null);
        navMapMapper.updateById(map);
        return NavMapVO.from(map);
    }

    /** 设置机器人侧地图名（切图时下发给 /agv/load_map 的名字，即 maps_dir 三件套文件名主干）。 */
    @Transactional(rollbackFor = Exception.class)
    public NavMapVO setRobotMapName(Long id, String robotMapName) {
        String name = robotMapName == null ? "" : robotMapName.trim();
        if (!name.isEmpty() && (name.length() > 128 || name.contains("..")
                || name.contains("/") || name.contains("\\"))) {
            throw new IllegalArgumentException("机器人地图名非法（非空且不含路径分隔符）: '" + name + "'");
        }
        NavMap map = findOrThrow(id);
        map.setRobotMapName(name.isEmpty() ? null : name);
        map.setUpdatedAt(null);
        navMapMapper.updateById(map);
        return NavMapVO.from(map);
    }

    /** 激活为当前部署地图（全局唯一 ACTIVE，其余归档）。 */
    @Transactional(rollbackFor = Exception.class)
    public NavMapVO activate(Long id) {
        NavMap map = findOrThrow(id);
        if (!NavMap.STATUS_ACTIVE.equals(map.getStatus())) {
            navMapMapper.selectList(Wrappers.lambdaQuery(NavMap.class)
                            .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE))
                    .forEach(active -> {
                        active.setStatus(NavMap.STATUS_ARCHIVED);
                        active.setUpdatedAt(null);
                        navMapMapper.updateById(active);
                    });
            map.setStatus(NavMap.STATUS_ACTIVE);
            map.setUpdatedAt(null);
            navMapMapper.updateById(map);
        }
        log.info("map activated: id={}, name={}", id, map.getMapName());
        return NavMapVO.from(map);
    }

    /** 删除地图（ACTIVE 或仍有关联点位/路线时拒绝）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        NavMap map = findOrThrow(id);
        if (NavMap.STATUS_ACTIVE.equals(map.getStatus())) {
            throw new IllegalStateException("地图处于 ACTIVE 状态，请先激活其他地图再删除");
        }
        if (navPointMapper.selectCount(Wrappers.lambdaQuery(NavPoint.class).eq(NavPoint::getMapId, id)) > 0) {
            throw new IllegalStateException("地图下仍有部署点位，请先删除全部点位");
        }
        if (navPathMapper.selectCount(Wrappers.lambdaQuery(NavPath.class).eq(NavPath::getMapId, id)) > 0) {
            throw new IllegalStateException("地图下仍有部署路线，请先删除全部路线");
        }
        navMapMapper.deleteById(id);
        afterCommitOrNow(() -> removeObjectQuietly(map.getObjectKey()));
    }

    /** 当前激活的部署地图（无则抛业务异常）。 */
    public NavMap requireActiveMap() {
        NavMap active = navMapMapper.selectOne(Wrappers.lambdaQuery(NavMap.class)
                .eq(NavMap::getStatus, NavMap.STATUS_ACTIVE)
                .last("LIMIT 1"));
        if (active == null) {
            throw new IllegalStateException("尚未激活部署地图，请先通过 /api/v1/nav-maps/{id}/activate 激活");
        }
        return active;
    }

    private NavMap findOrThrow(Long id) {
        NavMap map = navMapMapper.selectById(id);
        if (map == null) {
            throw new NotFoundException("地图 " + id + " 不存在");
        }
        return map;
    }

    private void requireMapNameAvailable(String mapName) {
        LambdaQueryWrapper<NavMap> wrapper = Wrappers.lambdaQuery(NavMap.class).eq(NavMap::getMapName, mapName);
        if (navMapMapper.selectCount(wrapper) > 0) {
            throw new IllegalStateException("地图名称已存在: " + mapName);
        }
    }

    private void putMapObject(String objectKey, byte[] compressed) {
        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(mapsBucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(compressed), compressed.length, -1)
                    .contentType("application/gzip")
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("地图数据写入 MinIO 失败: " + e.getMessage(), e);
        }
    }

    /** 每次保存前幂等建桶：桶可能被外部（如环境重置）删除，不缓存存在性。 */
    private synchronized void ensureBucket() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(mapsBucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(mapsBucket).build());
        }
    }

    private void removeObjectQuietly(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(mapsBucket).object(objectKey).build());
        } catch (Exception ignored) {
        }
    }

    private static MapGridVO toGridVO(LiveMapCache.OccupancyGrid grid) {
        return new MapGridVO(grid.frameId(), grid.resolution(), grid.width(), grid.height(),
                grid.originX(), grid.originY(), grid.originYaw(), grid.data(), grid.receivedAt());
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(raw.length / 2);
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(raw);
        }
        return buffer.toByteArray();
    }

    private static byte[] ungzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream buffer = new ByteArrayOutputStream(compressed.length * 4)) {
            gzip.transferTo(buffer);
            return buffer.toByteArray();
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

    public record StoredGrid(byte[] json) {
    }
}
