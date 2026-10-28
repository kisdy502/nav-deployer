-- RMS 任务执行记录：跨重启可查（此前仅内存 Map，重启即丢）。
-- 状态/语义与 RmsTaskStatus 对齐（idle/running/paused/completed/failed/canceled/...）。
CREATE TABLE IF NOT EXISTS rms_task (
    task_id                 VARCHAR(128) PRIMARY KEY,
    action_id               VARCHAR(32),
    task_type               VARCHAR(32),
    task_template_type      VARCHAR(64),
    template_code           VARCHAR(64),
    template_id             VARCHAR(64),
    parameters              TEXT,                            -- 原始 parameters JSON 快照
    status                  VARCHAR(32)      NOT NULL,
    message                 VARCHAR(1024),                   -- 终态原因（随 result_report 上报）
    dest_x                  DOUBLE PRECISION,
    dest_y                  DOUBLE PRECISION,
    dest_yaw                DOUBLE PRECISION,
    dest_topo_name          VARCHAR(64),
    internal_move_task_id   BIGINT,
    internal_charge_task_id BIGINT,
    created_at              TIMESTAMP        NOT NULL,
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rms_task_created_at ON rms_task (created_at DESC);
