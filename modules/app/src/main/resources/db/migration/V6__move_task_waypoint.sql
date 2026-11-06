ALTER TABLE move_task ADD COLUMN waypoint_id BIGINT;

COMMENT ON COLUMN move_task.waypoint_id IS 'TO_WAYPOINT 任务目标点位（map_waypoint.id）';
