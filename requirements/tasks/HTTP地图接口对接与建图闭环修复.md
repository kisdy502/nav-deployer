# 机器人 HTTP 地图接口对接与建图闭环修复记录

## 背景

机器人侧（simulated_chassis）架构变更：实时 2D 栅格改由专用 HTTP 服务提供（`GET :8092/maps/live`，gzip JSON + ETag/304，无 CORS 需后端代理），rosbridge `/map` 订阅白名单已移除；`get_map/list_maps` 等 service 保留。上位机需完成对接并修复建图闭环报错。

## 对接确认（8092）

- LiveMapCache 已具备 HTTP 拉取模式（ETag/If-None-Match/304/gzip 解码），`RosbridgeConfig#liveMapCache` 默认启用 `robotHttpUri(8092)/maps/live`；`map2d.use-rosbridge=true` 回退旧 WS 订阅（双通道）。本次验证装配无误，无新增改动
- 建图实时预览（前端 3s 轮询 `/api/v2/maps/live` → 后端按需拉 8092）链路自洽
- save 成功后终图获取仍走 `/agv/get_map` service（机器人侧保留）

## 建图报错修复（根因两个）

1. **双重超时过短**：机器人侧 lightning 保存最长 300s + 定位重启 120s 就绪窗，而上位机后端 SAVE_MAP 超时 90s、前端等待上限 100s——两次实测均死于超时（日志 11:23 / 11:45，`SAVE_MAP 超时（mode 未在期限内达到预期）`）
   - 后端：SAVE_MAP 任务 deadline 改用 `localizationTaskTimeoutMs`（660s = 机器人 600s + 60s 余量）
   - 前端：`awaitTaskTerminal` 参数化，SAVE_MAP 等待 700s
2. **前后端入库竞态**：save 成功后后端自动入库（syncRobotMapV2：get_map → 创建/替换 v2 地图并发布），而前端"完成建图并保存"同时 `createMapFromLiveV2` 同名创建——必撞"地图名称已存在"
   - 收敛：后端自动入库为唯一入口；前端 finish 流程改为"等 save 终态 → 轮询 listMapsV2 等 robot_map_name 命中（60s）→ 跳转新图"；「保存为 v2 地图」（非建图态）保留 from-live 手动创建

## 其他

- live 不可用报错文案对齐（提及 8092/定位未启动）
- 机器人侧新增 `/agv/cancel_mapping`（放弃建图恢复旧图，带 operation_id）：UR5e Docker profile 白名单尚未放行，暂不接；现有「放弃建图」（load_map 回旧图）仍可用
- 卡在建图状态自救：机器人 mode 实际回 NAVIGATION 后面板自动退出；若机器人侧卡 MAPPING，用「放弃建图」恢复
