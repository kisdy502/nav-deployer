-- ==================== V2：多楼层地图元数据（新地图格式） ====================
-- 对应 map-info.json 格式：每种元数据一张表；
-- 路线段表合并旧 nav_path_edge 的曲线字段（edge_type/control_points/back_up/reverse）。
-- 栅格（global.pgm 源数据）仍按既有惯例 gzip 存 MinIO，DB 只存元数据与 object_key。

-- 地图主表（map-info.json 的 mapInfo 头 + global.yaml 的宽高/分辨率/origin）
CREATE TABLE IF NOT EXISTS map (
    id                BIGSERIAL PRIMARY KEY,
    map_name          VARCHAR(64)       NOT NULL UNIQUE,
    coordinate_system VARCHAR(32)       NOT NULL DEFAULT 'map',
    unit              VARCHAR(16)       NOT NULL DEFAULT 'meters',
    version           VARCHAR(32)       NOT NULL DEFAULT 'v0.0.1', -- 语义化版本，每次变更自动 +1（v0.0.9 → v0.1.0）
    status            VARCHAR(16)       NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PUBLISHED（使用中，全局唯一）/ ARCHIVED
    modified_by       VARCHAR(64),
    source            VARCHAR(16)       NOT NULL DEFAULT 'LIVE',   -- LIVE / ROBOT_SYNC / IMPORT / MIGRATED
    robot_map_name    VARCHAR(64),
    resolution        DOUBLE PRECISION  NOT NULL DEFAULT 0.05,     -- 米/格
    width             INTEGER           NOT NULL DEFAULT 1,
    height            INTEGER           NOT NULL DEFAULT 1,
    origin_x          DOUBLE PRECISION  NOT NULL DEFAULT 0,
    origin_y          DOUBLE PRECISION  NOT NULL DEFAULT 0,
    origin_yaw        DOUBLE PRECISION  NOT NULL DEFAULT 0,
    object_key        VARCHAR(256),                                  -- MinIO 对象 key（gzip OccupancyGrid JSON）
    data_size         BIGINT,
    created_at        TIMESTAMP         NOT NULL,
    updated_at        TIMESTAMP
);

COMMENT ON TABLE map IS '多楼层地图主表：版本信息 + 全局栅格元数据';

-- 楼层表
CREATE TABLE IF NOT EXISTS map_floor (
    id                BIGSERIAL PRIMARY KEY,
    map_id            BIGINT            NOT NULL REFERENCES map(id) ON DELETE CASCADE,
    floor_code        VARCHAR(32)       NOT NULL,        -- 楼层唯一标识（F1 / F2）
    name              VARCHAR(128)      NOT NULL,
    elevation         DOUBLE PRECISION  NOT NULL DEFAULT 0,    -- 楼层基准高度（Z，米）
    default_max_speed DOUBLE PRECISION  NOT NULL DEFAULT 1.0,  -- 楼层默认最大速度（m/s）
    sort_order        INTEGER           NOT NULL DEFAULT 0,
    created_at        TIMESTAMP         NOT NULL,
    updated_at        TIMESTAMP,
    CONSTRAINT uk_map_floor_map_code UNIQUE (map_id, floor_code)
);

-- 点位表（waypoint：类型扩展为 charge/pickup/dropoff/storage/elevator/stairs/ramp/normal）
CREATE TABLE IF NOT EXISTS map_waypoint (
    id            BIGSERIAL PRIMARY KEY,
    map_id        BIGINT            NOT NULL REFERENCES map(id) ON DELETE CASCADE,
    floor_id      BIGINT            NOT NULL REFERENCES map_floor(id) ON DELETE CASCADE,
    waypoint_code VARCHAR(64)       NOT NULL,           -- WP_001
    name          VARCHAR(128),
    wp_type       VARCHAR(32)       NOT NULL DEFAULT 'normal',
    x             DOUBLE PRECISION  NOT NULL,           -- map 系坐标（米）
    y             DOUBLE PRECISION  NOT NULL,
    yaw           DOUBLE PRECISION  NOT NULL DEFAULT 0, -- 朝向（弧度）
    remark        VARCHAR(256),
    created_at    TIMESTAMP         NOT NULL,
    updated_at    TIMESTAMP,
    CONSTRAINT uk_map_waypoint_map_code UNIQUE (map_id, waypoint_code)
);

-- 区域表（zoneConstraints：forbidden 禁行 / slowDown 减速 / ramp 坡道）
CREATE TABLE IF NOT EXISTS map_zone (
    id          BIGSERIAL PRIMARY KEY,
    map_id      BIGINT            NOT NULL REFERENCES map(id) ON DELETE CASCADE,
    floor_id    BIGINT            NOT NULL REFERENCES map_floor(id) ON DELETE CASCADE,
    zone_code   VARCHAR(64)       NOT NULL,             -- ZC_001
    name        VARCHAR(128)      NOT NULL,
    zone_type   VARCHAR(16)       NOT NULL,
    priority    INTEGER           NOT NULL DEFAULT 2,   -- 数字越小优先级越高
    max_speed   DOUBLE PRECISION  NOT NULL DEFAULT 0,   -- 区域内最大速度（禁行区 0）
    shape       VARCHAR(16)       NOT NULL DEFAULT 'polygon',
    vertices    TEXT              NOT NULL,             -- JSON [[x,y],...]
    height      DOUBLE PRECISION,                       -- 区域高度（米），可空
    ramp_info   TEXT,                                   -- ramp 类型：JSON {slope,direction,startElevation,endElevation}
    description VARCHAR(512),
    created_at  TIMESTAMP         NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uk_map_zone_map_code UNIQUE (map_id, zone_code)
);

-- 货架表（shelves：footprint 占地多边形 + 边定义）
CREATE TABLE IF NOT EXISTS map_shelf (
    id            BIGSERIAL PRIMARY KEY,
    map_id        BIGINT            NOT NULL REFERENCES map(id) ON DELETE CASCADE,
    floor_id      BIGINT            NOT NULL REFERENCES map_floor(id) ON DELETE CASCADE,
    shelf_code    VARCHAR(64)       NOT NULL,           -- SH_001
    name          VARCHAR(128),
    vertices      TEXT              NOT NULL,           -- footprint 多边形顶点 JSON [[x,y],...]
    height        DOUBLE PRECISION  NOT NULL DEFAULT 1.8, -- 货架总高度（米）
    edges         TEXT,                                   -- 边定义 JSON [{edgeIndex,vertexStart,vertexEnd,direction?}]
    created_at    TIMESTAMP         NOT NULL,
    updated_at    TIMESTAMP,
    CONSTRAINT uk_map_shelf_map_code UNIQUE (map_id, shelf_code)
);

-- 货架层表（layers）
CREATE TABLE IF NOT EXISTS map_shelf_layer (
    id          BIGSERIAL PRIMARY KEY,
    shelf_id    BIGINT            NOT NULL REFERENCES map_shelf(id) ON DELETE CASCADE,
    layer_index INTEGER           NOT NULL DEFAULT 0,   -- 从 0 开始（底层 0）
    elevation   DOUBLE PRECISION  NOT NULL DEFAULT 0,   -- 相对楼层基准面高度偏移（米）
    access_edge INTEGER           NOT NULL DEFAULT 0,   -- 存取边索引（指向 footprint.edges）
    num_slots   INTEGER           NOT NULL DEFAULT 0,
    created_at  TIMESTAMP         NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uk_map_shelf_layer_idx UNIQUE (shelf_id, layer_index)
);

-- 货架格位表（slots，含商品信息）
CREATE TABLE IF NOT EXISTS map_shelf_slot (
    id           BIGSERIAL PRIMARY KEY,
    layer_id     BIGINT            NOT NULL REFERENCES map_shelf_layer(id) ON DELETE CASCADE,
    shelf_id     BIGINT            NOT NULL REFERENCES map_shelf(id) ON DELETE CASCADE,
    slot_index   INTEGER           NOT NULL DEFAULT 0,
    x            DOUBLE PRECISION  NOT NULL,            -- 格位中心三维坐标（米）
    y            DOUBLE PRECISION  NOT NULL,
    z            DOUBLE PRECISION  NOT NULL,
    width        DOUBLE PRECISION  NOT NULL DEFAULT 0.4, -- 格位尺寸 [width,depth,height]
    depth        DOUBLE PRECISION  NOT NULL DEFAULT 0.6,
    height       DOUBLE PRECISION  NOT NULL DEFAULT 0.3,
    product_id   VARCHAR(64),                           -- 商品 id（空位为 NULL）
    product_name VARCHAR(128),
    created_at   TIMESTAMP         NOT NULL,
    updated_at   TIMESTAMP,
    CONSTRAINT uk_map_shelf_slot_idx UNIQUE (layer_id, slot_index)
);

-- 路线表（paths：from/to + 途经点位链 + 限速/避障）
CREATE TABLE IF NOT EXISTS map_path (
    id                       BIGSERIAL PRIMARY KEY,
    map_id                   BIGINT            NOT NULL REFERENCES map(id) ON DELETE CASCADE,
    floor_id                 BIGINT            NOT NULL REFERENCES map_floor(id) ON DELETE CASCADE,
    path_code                VARCHAR(64)       NOT NULL, -- PATH_001
    name                     VARCHAR(128),
    from_waypoint_id         BIGINT            NOT NULL REFERENCES map_waypoint(id),
    to_waypoint_id           BIGINT            NOT NULL REFERENCES map_waypoint(id),
    is_bidirectional         BOOLEAN           NOT NULL DEFAULT TRUE,
    max_speed                DOUBLE PRECISION,           -- 空 = 使用楼层 default_max_speed
    enable_obstacle_avoidance BOOLEAN          NOT NULL DEFAULT TRUE,
    status                   VARCHAR(16)       NOT NULL DEFAULT 'DRAFT', -- DRAFT / DEPLOYED / DISABLED
    created_at               TIMESTAMP         NOT NULL,
    updated_at               TIMESTAMP,
    CONSTRAINT uk_map_path_map_code UNIQUE (map_id, path_code)
);

-- 路线段表（paths 相邻点位之间的段；合并旧 nav_path_edge 的曲线字段）
CREATE TABLE IF NOT EXISTS map_path_segment (
    id                  BIGSERIAL PRIMARY KEY,
    path_id             BIGINT            NOT NULL REFERENCES map_path(id) ON DELETE CASCADE,
    seq                 INTEGER           NOT NULL,      -- 从 1 开始
    source_waypoint_id  BIGINT            NOT NULL REFERENCES map_waypoint(id),
    target_waypoint_id  BIGINT            NOT NULL REFERENCES map_waypoint(id),
    segment_type        VARCHAR(16)       NOT NULL DEFAULT 'STRAIGHT', -- STRAIGHT / CURVE（旧格式曲线能力合并于此）
    control_points      TEXT,                            -- CURVE 时 JSON [{x,y},...]，1~2 个贝塞尔控制点
    max_speed           DOUBLE PRECISION,                -- 段级限速（空 = 用路线限速/楼层默认）
    back_up             BOOLEAN           NOT NULL DEFAULT FALSE,
    reverse             BOOLEAN           NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP         NOT NULL,
    updated_at          TIMESTAMP,
    CONSTRAINT uk_map_path_segment_seq UNIQUE (path_id, seq)
);

-- 楼层间连接表（interFloorConnections：elevator / stairs / ramp）
CREATE TABLE IF NOT EXISTS map_inter_floor_conn (
    id          BIGSERIAL PRIMARY KEY,
    map_id      BIGINT            NOT NULL REFERENCES map(id) ON DELETE CASCADE,
    conn_code   VARCHAR(64)       NOT NULL,             -- CONN_001
    name        VARCHAR(128),
    conn_type   VARCHAR(16)       NOT NULL,             -- elevator / stairs / ramp
    connections TEXT              NOT NULL,             -- JSON [{floorId,waypointId}]（楼层/点位编码）
    capacity    DOUBLE PRECISION,                       -- 载重限制（kg，空 = 无限制）
    max_speed   DOUBLE PRECISION,                       -- 通道内最大速度（m/s）
    ramp_info   TEXT,                                   -- ramp 类型：JSON {slope,length,surface}
    created_at  TIMESTAMP         NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uk_map_inter_floor_conn_code UNIQUE (map_id, conn_code)
);

CREATE INDEX IF NOT EXISTS idx_map_floor_map ON map_floor(map_id);
CREATE INDEX IF NOT EXISTS idx_map_waypoint_floor ON map_waypoint(floor_id);
CREATE INDEX IF NOT EXISTS idx_map_zone_floor ON map_zone(floor_id);
CREATE INDEX IF NOT EXISTS idx_map_shelf_floor ON map_shelf(floor_id);
CREATE INDEX IF NOT EXISTS idx_map_path_floor ON map_path(floor_id);
CREATE INDEX IF NOT EXISTS idx_map_path_segment_path ON map_path_segment(path_id);
CREATE INDEX IF NOT EXISTS idx_map_inter_floor_conn_map ON map_inter_floor_conn(map_id);
