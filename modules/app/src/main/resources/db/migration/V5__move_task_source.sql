-- move_task 来源标识：区分手动（前端 REST）/ RMS 下发 / 充电任务内部导航。
-- manual            = 前端页面手动创建（POST /api/v1/move-tasks）
-- rms:<task_id>     = RMS 下发的导航/移动任务（task/add + task/start 派发）
-- charge:<chargeId> = 充电任务的内部导航（去桩路线/接近段）
-- 存量行没有来源信息，按 manual 兜底。
ALTER TABLE move_task ADD COLUMN source VARCHAR(128) NOT NULL DEFAULT 'manual';

COMMENT ON COLUMN move_task.source IS '来源标识: manual=手动; rms:<task_id>=RMS下发; charge:<chargeId>=充电内部导航';
