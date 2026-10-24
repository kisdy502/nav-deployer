-- ==================== V3：点位类型词表对齐真机（11 类） ====================
-- 去掉 normal（普通点），由 path（路径点）替代默认类型；新增：
-- relocation 重定位点 / parking 归巢点 / workstation 作业点
UPDATE map_waypoint SET wp_type = 'path' WHERE wp_type = 'normal';

ALTER TABLE map_waypoint ALTER COLUMN wp_type SET DEFAULT 'path';
