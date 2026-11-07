# RMS 对接域 DDD 搬迁实现记录

所属故事: ../stories/迁移RMS对接域到DDD模块.md

## 模块结构（modules/rms，com.agv.navdeployer.rms）

- interfaces/：RmsTaskOpsController（自 app 迁入，URL 不变）
- application/：RmsTaskService、RmsMapService、RmsTelemetryService、RobotStateView + dto/RmsTaskDTO（原 RmsTaskVO）
- domain/：RmsTask、RmsTaskStatus、RmsTaskRegistry、RmsTemplateStore、RmsResultReporter、RmsMapState
- infrastructure/：zenoh/（ZenohChannel、HostStats）、gateway/（Report/Command/Service 三网关）、session/、protocol/（keys + dto.command/report/service）、config/（RmsConfig、RmsProperties）、log/、teach/、inspection/、repository/（RmsTaskRecord + Mapper，自 platform 归位）

## 决策记录

- RmsTaskService（827 行）整文件迁移不拆分：RmsTask/Registry/ResultReporter 已具备聚合形态，行为零改动优先（冒烟兜底不足下的风险控制）；拆分作为后续治理项
- domain 零 zenoh import、零 protocol dto 引用（git grep 验证）
- 跨 BC 仅依赖 task.application（SmartNavigation/Charge/MapModeTask）与 map.application（MapMetaService/MapPackageService）

## 验证情况

- `mvnw clean package` 全仓通过
- 待云端联调冒烟：AC2（注册/心跳/上报）、AC3（派发导航→result-report）、AC4（充电模板）
