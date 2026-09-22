CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    age         INTEGER,
    avatar_key  VARCHAR(256),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP
);

-- ============ 部署程序：地图 / 点位 / 路线 / 移动任务 ============

CREATE TABLE IF NOT EXISTS nav_map (
    id          BIGSERIAL PRIMARY KEY,
    map_name    VARCHAR(64)       NOT NULL UNIQUE,
    status      VARCHAR(16)       NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ACTIVE / ARCHIVED
    source      VARCHAR(16)       NOT NULL DEFAULT 'LIVE',
    robot_map_name VARCHAR(64),                                  -- 机器人侧地图名（maps_dir 三件套文件名主干）
    resolution  DOUBLE PRECISION  NOT NULL,
    width       INTEGER           NOT NULL,
    height      INTEGER           NOT NULL,
    origin_x    DOUBLE PRECISION  NOT NULL,
    origin_y    DOUBLE PRECISION  NOT NULL,
    origin_yaw  DOUBLE PRECISION  NOT NULL,
    object_key  VARCHAR(256)      NOT NULL,
    data_size   BIGINT,
    created_at  TIMESTAMP         NOT NULL,
    updated_at  TIMESTAMP
);

-- 已有库的增量迁移（schema.sql 仅在初始化时建表，这里幂等补列）
ALTER TABLE nav_map ADD COLUMN IF NOT EXISTS robot_map_name VARCHAR(64);

CREATE TABLE IF NOT EXISTS nav_point (
    id          BIGSERIAL PRIMARY KEY,
    map_id      BIGINT            NOT NULL REFERENCES nav_map(id),
    point_code  VARCHAR(64)       NOT NULL,
    point_type  VARCHAR(16)       NOT NULL DEFAULT 'NORMAL', -- NORMAL / CHARGER / HOME
    x           DOUBLE PRECISION  NOT NULL,
    y           DOUBLE PRECISION  NOT NULL,
    yaw         DOUBLE PRECISION  NOT NULL,
    remark      VARCHAR(256),
    created_at  TIMESTAMP         NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uk_nav_point_map_code UNIQUE (map_id, point_code)
);

CREATE TABLE IF NOT EXISTS nav_path (
    id          BIGSERIAL PRIMARY KEY,
    map_id      BIGINT            NOT NULL REFERENCES nav_map(id),
    path_code   VARCHAR(64)       NOT NULL,
    path_name   VARCHAR(128),
    status      VARCHAR(16)       NOT NULL DEFAULT 'DRAFT',  -- DRAFT / DEPLOYED / DISABLED
    created_at  TIMESTAMP         NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT uk_nav_path_map_code UNIQUE (map_id, path_code)
);

CREATE TABLE IF NOT EXISTS nav_path_edge (
    id                BIGSERIAL PRIMARY KEY,
    path_id           BIGINT          NOT NULL REFERENCES nav_path(id) ON DELETE CASCADE,
    seq               INTEGER         NOT NULL,
    source_point_id   BIGINT          NOT NULL REFERENCES nav_point(id),
    target_point_id   BIGINT          NOT NULL REFERENCES nav_point(id),
    edge_type         VARCHAR(16)     NOT NULL DEFAULT 'STRAIGHT', -- STRAIGHT / CURVE
    control_points    TEXT,           -- JSON 数组 [{"x":..,"y":..}]，1~2 个（贝塞尔）
    max_speed         DOUBLE PRECISION NOT NULL DEFAULT 0.6,
    back_up           BOOLEAN         NOT NULL DEFAULT FALSE,
    reverse           BOOLEAN         NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_nav_path_edge_seq UNIQUE (path_id, seq)
);

CREATE TABLE IF NOT EXISTS move_task (
    id             BIGSERIAL PRIMARY KEY,
    task_no        VARCHAR(64)      NOT NULL UNIQUE,  -- follow_edge command_id，uuid
    task_type      VARCHAR(16)      NOT NULL,         -- TO_POINT / GOAL / FOLLOW_PATH
    status         VARCHAR(16)      NOT NULL,         -- CREATED/DISPATCHED/EXECUTING/SUCCEEDED/FAILED/CANCELLED/TIMEOUT
    point_id       BIGINT,
    path_id        BIGINT,
    goal_x         DOUBLE PRECISION,
    goal_y         DOUBLE PRECISION,
    goal_theta     DOUBLE PRECISION,
    current_x      DOUBLE PRECISION,
    current_y      DOUBLE PRECISION,
    current_theta  DOUBLE PRECISION,
    agv_state      VARCHAR(32),
    error_message  VARCHAR(512),
    goal_id        VARCHAR(64),                       -- rosbridge send_action_goal 的 id
    segment_seq    INTEGER,                           -- FOLLOW_PATH 当前段（从 1 开始）
    segment_total  INTEGER,
    created_at     TIMESTAMP        NOT NULL,
    updated_at     TIMESTAMP,
    finished_at    TIMESTAMP
);
