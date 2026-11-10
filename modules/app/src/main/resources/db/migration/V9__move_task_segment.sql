CREATE TABLE move_task_segment (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    seq INT NOT NULL,
    source_code VARCHAR(64),
    target_code VARCHAR(64),
    target_x DOUBLE PRECISION,
    target_y DOUBLE PRECISION,
    edge_type VARCHAR(32),
    max_speed DOUBLE PRECISION,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(500),
    created_at TIMESTAMP DEFAULT now(),
    finished_at TIMESTAMP,
    UNIQUE (task_id, seq)
);
CREATE INDEX idx_move_task_segment_task ON move_task_segment (task_id);
