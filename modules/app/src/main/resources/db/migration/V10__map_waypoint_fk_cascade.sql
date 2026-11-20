-- 修复删除地图/点位时的外键级联断裂：
-- map_path(map_path_segment) 引用 map_waypoint 的外键原本无 ON DELETE CASCADE，
-- 删 map 时 waypoint 与 path 两条级联路径顺序不定，waypoint 先删即触发
-- "still referenced from map_path_segment" 500（菱形级联缺陷）。
ALTER TABLE map_path
    DROP CONSTRAINT IF EXISTS map_path_from_waypoint_id_fkey,
    DROP CONSTRAINT IF EXISTS map_path_to_waypoint_id_fkey,
    ADD CONSTRAINT map_path_from_waypoint_id_fkey
        FOREIGN KEY (from_waypoint_id) REFERENCES map_waypoint(id) ON DELETE CASCADE,
    ADD CONSTRAINT map_path_to_waypoint_id_fkey
        FOREIGN KEY (to_waypoint_id) REFERENCES map_waypoint(id) ON DELETE CASCADE;

ALTER TABLE map_path_segment
    DROP CONSTRAINT IF EXISTS map_path_segment_source_waypoint_id_fkey,
    DROP CONSTRAINT IF EXISTS map_path_segment_target_waypoint_id_fkey,
    ADD CONSTRAINT map_path_segment_source_waypoint_id_fkey
        FOREIGN KEY (source_waypoint_id) REFERENCES map_waypoint(id) ON DELETE CASCADE,
    ADD CONSTRAINT map_path_segment_target_waypoint_id_fkey
        FOREIGN KEY (target_waypoint_id) REFERENCES map_waypoint(id) ON DELETE CASCADE;
