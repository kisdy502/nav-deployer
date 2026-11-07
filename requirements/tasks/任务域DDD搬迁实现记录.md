# 任务域 DDD 搬迁实现记录

所属故事: ../stories/迁移任务域到DDD模块.md

## 模块结构（modules/task，com.agv.navdeployer.task）

- interfaces/：MoveTaskController、MapModeTaskController、ChargeController（自 app 迁入，URL/响应零变化）
- application/：MoveTaskService、MapModeTaskService、ChargeService、SmartNavigationService、MapAlignmentGuard、RobotOperationalService
  - cmd/：MoveTaskCreateCmd、SaveMapCmd、MapSwitchCmd、ChargeStartCmd（原 ChargeCommand）
  - dto/：MoveTaskDTO、MapModeTaskDTO、ChargeTaskDTO（record，Jackson snake_case 输出与原 VO 一致）
- domain/：
  - movetask/MoveTask（充血：checkTerminal / markDispatchedGoal / markExecutingIfDispatched / markFinished）+ TaskErrorCode（含 WAYPOINT/PATH_NOT_FOUND）
  - mapmodetask/MapModeStateMachine（零 robot-core 依赖：onOperation 参数化，LocalizationOperation 在 application 层转换）
  - charge/ChargeTask（public 化 + accessor 补全，vo() 转移到 application）、ChargeProperties
  - repository/MoveTaskRepository（含 markStaleInterrupted 批量恢复语义）、port/RobotConnectionPort
- infrastructure/：MoveTaskRepositoryImpl + mapper/MoveTaskMapper、mapstruct/MoveTaskMapstruct、robot/RobotConnectionAdapter（RosbridgeClient 桥接）

## 联动改动

- 根 pom modules/dependencyManagement + nav-task；app/rms pom + nav-task 依赖（rms → task 符合依赖图）
- rms 5 文件、app 3 文件（RobotController/MapV2Controller/SystemConfigController）引用切到新包
- platform 移除任务域 15 文件（git mv 保历史）

## 决策记录

- AC5 口径修订（story 内已记录）：过渡期 RosCommandDispatcher/Telemetry/SsePublisher 等 robot-core 门面在 application 注入使用；RosbridgeClient 全部经 RobotConnectionPort 端口；完整指令端口化在机器人接入域 story 完成
- MoveTask 充血化收敛为四个领域方法（状态判断/流转下沉实体），不引入状态枚举重构（DB 字符串映射风险）
- ChargeTaskDTO 转换在 application（vo 静态方法），ChargeTask 不依赖 dto 层

## 验证情况

- `mvnw clean compile` 全仓通过；domain 零 robot-core import；application 零 RosbridgeClient import（git grep 验证）
- 待仿真环境冒烟：AC2（五种任务类型执行）、AC3（取消/看门狗）、AC4（充电含 RMS 入口）、AC6（启动无错）
