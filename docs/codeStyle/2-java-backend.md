# Java 后端开发规范

> **适用范围**：`business-group/backend-group/*` 下的所有 Java 后端微服务。
>
> **两种结构并存，不强制统一**：
> - **传统分层**是默认形态，也是绝大多数包的现状：`controller` / `service` + `service/impl/` / `domain{entity,model,dto,vo,query,convert}` / `mapper`
> - **DDD 四层**目前有两处在用：`robot-management-service` 的 `robot.ota` 包、`quality-inspection-service` 的 `quality.scheduling` 包——均为 `interfaces` / `application` / `domain` / `infrastructure`
>
> **不要求改造存量代码，也不要求新代码改用另一种结构。** 全文用下列标记标注约束力：
>
> | 标记 | 含义 |
> | --- | --- |
> | 【强制】 | 所有 Java 后端包都要遵守 |
> | 【DDD 强制】 | 只在 DDD 四层包内生效（`robot.ota`、`quality.scheduling`） |
> | 【建议】 | 推荐做法，新代码尽量遵守；**不做红线**，不要求回头改老代码 |
>
> **唯一跨包强制的技术约束：业务逻辑写进 domain 对象里**（第 3 节）。这是本项目对代码质量的底线，与用哪种包结构无关。

---

## 1. 框架与技术栈

| 项 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 21 |
| 主框架 | Spring Boot 3.3.5 / Spring Cloud 2023.0.3 |
| ORM | MyBatis-Plus |
| 数据库 | PostgreSQL（迁移用 Flyway） |
| 配置中心 | Nacos |
| 缓存 | Redis |
| 日志 | SLF4J + Logback |
| 对象转换 | MapStruct |
| 接口文档 | Swagger（`@Tag` / `@Operation` / `@Schema`） |

---

## 2. 两种项目结构

### 2.1 对照

| 维度 | 传统分层（默认） | DDD 四层（ota / scheduling） |
| --- | --- | --- |
| 现状范围 | 除 ota、scheduling 外的所有包 | `robot-management-service` 的 `robot.ota`；`quality-inspection-service` 的 `quality.scheduling` |
| 包结构 | controller / service / domain / mapper | interfaces / application / domain / infrastructure |
| `domain` 包的定位 | **数据载体**：entity、model、dto、vo、query、convert | **领域层**：实体行为、值对象、领域服务、Repository 接口 |
| 实体与持久化 | Entity 与 DTO / VO 分离 | 领域实体可自带 ORM 注解 |
| 数据访问 | Service 直接注入 Mapper | domain 定 Repository 接口，infrastructure 实现 |

### 2.2 传统分层包结构（默认）

以 `robot-management-service` 为例：

```
com.zioneer.robotmanager.robot/
├── controller/                     # REST 入口（含 external/ 等子包）
├── service/ + service/impl/        # Service 接口 + 实现
├── domain/
│   ├── entity/                     # MyBatis-Plus Entity（@TableName），与表对应
│   ├── model/                      # 运行时业务模型（不持久化）
│   ├── dto/                        # 请求 / 命令 DTO
│   ├── vo/                         # 响应 VO
│   ├── query/                      # 查询条件对象
│   └── convert/                    # MapStruct 转换器
├── mapper/                         # MyBatis-Plus Mapper
├── feign/                          # Feign 客户端
├── job/  listener/  mq/            # 定时任务 / 事件端点 / 消息
└── config/  constant/  error/  support/
```

沿用各服务 `AGENTS.md` 已有的约定。其中两条最容易与本规范混淆，单独强调：

- **Entity / DTO / VO 必须分离，不允许直接返回 Entity**
- **Converter（MapStruct）只负责字段搬运和轻量映射，不承接业务编排**

### 2.3 DDD 四层包结构（ota / scheduling）

`robot-management-service` 的 `robot.ota/`：

```
ota/
├── interfaces/                     # HTTP 入口（Controller）
├── application/                    # 用例编排、事务边界、事件发布
│   ├── cmd/  query/  dto/          # 入参 / 出参对象
│   └── listener/                   # 事件与消息监听
├── domain/                         # 领域层：核心业务逻辑
│   ├── {subDomain}/                # 子域
│   │   ├── Xxx.java                # 实体（含状态与行为）
│   │   ├── state/                  # 状态枚举
│   │   ├── event/                  # 领域事件
│   │   └── service/                # 领域服务（多实体协作时）
│   ├── service/                    # 跨子域领域服务
│   ├── vo/                         # 值对象
│   └── repository/                 # 仓储接口（实现在 infrastructure）
└── infrastructure/                 # 基础设施
    ├── repository/                 # 仓储实现
    │   └── mapper/                 # MyBatis-Plus Mapper
    ├── dataobject/                 # 数据对象 DO
    ├── mapstruct/                  # DO ↔ Domain 转换
    └── client/                     # 远程调用
```

`quality-inspection-service` 的 `quality.scheduling/`：

```
scheduling/
├── interfaces/                     # HTTP 入口（SchedulingInternalController，内部调度接口）
├── scheduled/                      # 定时任务入口（StationDispatchScanJob）；@Scheduled 只放这里
├── application/                    # 用例编排、事务边界
│   ├── dto/                        # 出参 VO
│   └── *Service.java               # StationSchedulingService（扫描调度）/ StationSchedulingStateService（状态自愈/暂停/按键恢复）/ SchedulingScanLock（全局扫描锁）
├── domain/
│   ├── batch/                      # 批次子域（InspectionBatchPlan / BatchSlotOrder）
│   ├── station/                    # 站点投影与调度状态（InspectionStation / InspectionStationSchedulingState / PauseReason）
│   ├── workorder/                  # 工单调度投影（InspectionWorkOrder）
│   ├── config/                     # 调度配置
│   └── repository/                 # 仓储接口（InspectionStationRepository / InspectionWorkOrderRepository / SchedulingStateRepository）
└── infrastructure/
    ├── repository/                 # 仓储实现（本域 Mapper 查询 + 委托主域 Service 完成跨域用例）
    └── mapstruct/                  # 主域 Entity ↔ 调度投影
```

与 ota 的三点差异：

| 差异 | scheduling 的做法 | 原因 |
| --- | --- | --- |
| 定时任务入口 | 独立 `scheduled/` 包，与 `interfaces` 并列 | `@Scheduled` 不混入 application；入口层只做触发，逻辑在 application |
| 领域模型形态 | 用**投影 record**（`InspectionStation`、`InspectionWorkOrder`），经 Mapstruct 从主域 Entity 转换 | 调度包计划整体迁移为独立调度服务，投影即未来的 Feign DTO，迁移时替换 Repository 实现即可 |
| Repository 跨域门面 | 查询走本域 Mapper；写操作用例（建单 / 取消 / 拉起任务流）委托主域应用服务 | 调度不重复实现工单生命周期，只定义调度视角需要的能力；主域对调度域的依赖通过共享枚举（`domain.model.InspectionMode`）收敛 |

### 2.4 什么时候新开 DDD 四层【建议】

**不强制**。过往那套"复杂业务域必须 DDD"的硬性选型已取消——默认就是传统分层，DDD 四层按需选用。

新增业务域时，下列信号越多，越值得考虑独立成 DDD 四层包；否则沿用传统分层即可。判断由开发者与老大共同决定，不由规则强制：

- 业务规则密集（同一处需要维护多条规则）
- 实体状态流转复杂（多个状态需要扭转）
- 同一用例涉及多个聚合 / 实体协作
- 存在业务专属名词，需要聚合、值对象来表达不变量
- 预期 6 个月内会持续新增规则或状态

反过来，如果出现下列信号，说明**当前代码里的业务逻辑正在失去归属**，应当先把逻辑搬进 domain 对象（第 3 节），而不是急着换包结构：

- 同一处出现多次"按状态分支处理"
- 状态字段开始用 if-else 判断而非 enum
- 新增业务流程时，发现现有结构无法容纳

---

## 3. domain：唯一强制的要求【强制】

**本规范唯一的硬约束，全工作区所有 Java 后端包都适用，与包结构无关。**

一句话：**所有业务逻辑都写到自身的 domain 里**。业务规则、判断、计算、状态流转，由拥有数据的对象自己承担，不散落在 Service / Controller / 工具类里。

传统分层里的 `domain/` 虽然以数据载体为主（entity / dto / vo），但**它同样是业务逻辑的归属地**——`domain/entity/RobotEntity`、`domain/model/RobotBodyStatus` 这类对象应当拥有自己的业务行为方法。

### 3.1 四条设计要点

1. **属性优先聚合成对象**：domain 中的属性如果能聚合成一个有业务含义的对象，就优先聚合，而不是平铺成散落字段。例如 `posX`、`posY`、`posZ` 应聚合成 `Position`，由 `Position` 自己封装坐标校验与距离计算，而不是让外界取 x、y、z 出来自己算。

2. **命名与业务一致**：名称决定业务逻辑的归属。命名 `TimeRange`，`contains()`、`checkOverlap()` 自然就放进去；命名 `StartTimeAndEndTime`，就不会想到里面放方法。好的命名让代码自文档化。

3. **多个 domain 经常相互使用 → 考虑构建新 domain**：当 `Robot` 与 `RobotType` 总是一起判断，就把它们的共同规则抽到一个协作对象里。这是从协作中识别新抽象的能力，也是 domain 设计持续进化的关键。

4. **能传对象就别拆成散字段**：方法（尤其构造方法）能收一个现成对象解决问题时，就收对象。`new ActionStatusReport(payload)` 显然比 `new ActionStatusReport(actionId, status, failureDetail, reportedAt)` 内聚——后者把「这个对象由哪几个字段构成」的知识复制到了每一个调用点，字段一增一减处处要改。调用方手上已经握着那个对象时，尤其不要为了传参把它拆开。

### 3.2 判断与计算的落点

判断逻辑和计算逻辑放在**拥有该数据的对象**中。所有对象（实体、值对象、DTO 等）都遵守此原则。

```java
// ❌ 不要：在 Service 层取平铺字段做判断和计算
if (robot.getHomeX() != null || robot.getHomeY() != null || robot.getHomeZ() != null) { ... }
var total = order.getItems().stream().mapToInt(item -> item.getPrice() * item.getQuantity()).sum();

// ✅ 应当：判断和计算封装在拥有数据的对象中
public class Position {
    private BigDecimal x; private BigDecimal y; private BigDecimal z;

    public boolean hasValue() { return x != null || y != null || z != null; }
}

public class Robot {
    private Position homePoint;

    public boolean checkHasHomePoint() { return homePoint != null && homePoint.hasValue(); }
}

public class Order {
    public BigDecimal calculateTotal() {
        return items.stream().map(...).reduce(...);
    }
}

// Service 中直接调用，不关心内部细节
if (robot.checkHasHomePoint()) { ... }
var total = order.calculateTotal();
```

### 3.3 状态判断

状态用 enum 表达，状态判断封装成实体的方法，而不是在外部拿状态值做 if-else。

```java
// 状态枚举（参见 robot-management-service 的 domain/model/TaskStatus.java）
public enum TaskStatus {
    PENDING, PROCESSING, SUCCEEDED, FAILED;

    private static final Set<TaskStatus> TERMINAL_STATUS = EnumSet.of(SUCCEEDED, FAILED);

    public boolean checkTerminal() { return TERMINAL_STATUS.contains(this); }

    public static TaskStatus from(String status) {
        return Optional.ofNullable(EnumUtils.getEnum(TaskStatus.class, status))
            .orElseThrow(() -> new IllegalArgumentException("Invalid actionStatus: " + status));
    }
}

// 实体中封装判断方法
public class RobotTask {
    private TaskStatus status;

    public boolean checkCompleted()  { return status != null && status.checkTerminal(); }
    public boolean checkProcessing() { return status != null && Objects.equals(TaskStatus.PROCESSING, status); }
}
```

外部使用：

```java
if (task.checkCompleted()) { ... }                     // ✅
if (task.getStatus() == TaskStatus.SUCCEEDED) { ... }  // ❌ 应改成上面的写法
```

### 3.4 存量代码怎么处理

这条约束针对**新写的代码**和**正在改动的代码**：

- 改一个方法时，如果里面的业务判断本来就该属于某个 domain 对象，顺手搬进去
- 不要为了"合规"专门发起一次全仓重构
- 存量代码保持原样，不在无关改动里顺手调整

---

## 4. DDD 四层细则（ota / scheduling）

> 以下内容**只在 DDD 四层包内生效**（`robot.ota`、`quality.scheduling`）。其他包可作参考，但不受约束。

### 4.1 依赖方向【DDD 强制】

严格单向：

```
interfaces ──▶ application ──▶ domain ◀── infrastructure
                                    │
                                    └── 实现 domain.repository.Repository
```

### 4.2 各层职责【DDD 强制】

| 层 | 职责 | 禁止 |
| --- | --- | --- |
| interfaces | 接收请求、参数校验、调用应用层 Service、返回响应 | 不写业务逻辑 |
| application | 编排领域对象和领域服务、事务控制、事件发布 | 不直接操作字段（不用 getter/setter 做判断）；不直接调用 Mapper |
| domain | 封装业务规则、状态变更、数据校验、仓储接口定义 | 不依赖基础设施、不依赖 Spring |
| infrastructure | 持久化实现、外部服务调用、技术适配 | 不包含业务逻辑 |

### 4.3 命名规范【DDD 强制】

| 类型 | 所在层 | 命名规则 | 示例 |
| --- | --- | --- | --- |
| Controller | interfaces | 名词 + Controller | `OtaTaskController` |
| Service | application | 名词 + Service | `OtaTaskService` |
| 命令对象 | application/cmd | 动词 + 名词 + Cmd | `CreateOtaTaskCmd` |
| 查询对象 | application/query | 名词 + Query | `OtaTaskQuery` |
| DTO | application/dto | 名词 + DTO | `OtaTaskDTO` |
| 领域实体 | domain | 名词，表达业务含义 | `OtaTask` |
| 领域服务 | domain/service | 名词 + DomainService | `OtaTaskDomainService` |
| Repository 接口 | domain/repository | 名词 + Repository | `OtaTaskRepository` |
| Repository 实现 | infrastructure/repository | 名词 + RepositoryImpl | `OtaTaskRepositoryImpl` |
| Mapper | infrastructure/repository/mapper | 名词 + Mapper | `OtaTaskMapper` |
| 数据对象 | infrastructure/dataobject | 名词 + DO | `OtaTaskDO` |
| 转换器 | infrastructure/mapstruct | 名词 + Mapstruct | `OtaTaskMapstruct` |
| 领域事件 | domain/event | 业务动作 + 过去式 + Event | `OtaTaskStatusChangedEvent` |
| 事件监听器 | application/listener | 名词 + EventListener | `OtaTaskStatusChangedEventListener` |
| 外部客户端 | infrastructure/client | 名词 + FeignClient | `XxxFeignClient` |
| 状态枚举 | domain/state | 名词 + Status | `TaskStatus` |
| 值对象 | domain | 名词 + VO | `PositionVO` |

### 4.4 interfaces 层【DDD 强制】

- 只做参数校验（`@Valid`）和调用应用层 Service
- 查询接口必须封装为 `XxxQuery` 对象，禁止使用多个 `@RequestParam` 散落参数
- API 方法必须加 Swagger 注解，写法见 5.1

```java
@Tag(name = "OTA 任务接口")
@RestController
@RequestMapping(APIPath.OTA_TASK)
public class OtaTaskController {

    private final OtaTaskService otaTaskService;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "创建 OTA 任务")
    public OtaTask create(@RequestBody @Valid CreateOtaTaskCmd cmd) {
        return otaTaskService.create(cmd);
    }
}
```

### 4.5 application 层【DDD 强制】

- 编排用例：构造领域对象 → 校验 → 保存 → 发布事件
- 事务边界：`@Transactional` 加在 application 方法上
- 事件发布在 application 层统一处理
- 方法不超过 20 行，超过则拆分

```java
@Service
public class OtaTaskService {

    @Autowired
    private OtaTaskRepository otaTaskRepository;

    @Transactional
    public OtaTask create(CreateOtaTaskCmd cmd) {
        var task = new OtaTask(cmd);
        task.validate();
        otaTaskRepository.save(task);
        eventPublisher.publishEvent(new OtaTaskStatusChangedEvent(task));
        return task;
    }
}
```

反模式：

| 反模式 | 后果 | 正确做法 |
| --- | --- | --- |
| 应用层 Service 直接判断业务规则（if 状态分支） | 业务规则散落多处 | 单实体判断下沉到实体方法；跨实体判断抽到领域服务 |
| 应用层 Service 直接调用 Mapper | 绕过 Repository，事务和审计失效 | 通过 Repository 接口操作数据 |
| 应用层 Service 上无 `@Transactional` | 跨实体写入可能不一致 | 加上 `@Transactional` |
| 应用层 Service 出现 `@Scheduled` / `@Async` | 混入基础设施关注点 | 移到独立的 listener / scheduled 包 |

### 4.6 domain 层【DDD 强制】

第 3 节的四条设计要点在此同样适用。此外：

#### 实体

**创建对象用构造函数，更新对象用领域方法，查询判断用领域方法**——禁止外部用 setter 拼装、用 getter 取值做判断。

```java
// ✅
var task = new OtaTask(robotCode, dto, (short) 1);
existing.updateBy(dto);
if (subTask.checkCompleted()) return;

// ❌
var task = new OtaTask();
task.setStepCode(dto.getStepCode());
existing.setStatus("SUCCESS");
if ("SUCCESS".equals(subTask.getStatus())) { ... }
```

**Lombok 与 ORM**：DDD 模式下领域实体可带 `@Getter`/`@Setter`（Setter 仅用于框架反序列化）、`@NoArgsConstructor`、`@TableName`。Getter 允许在 Repository 查询条件和 DTO 转换中使用，业务编排中不允许用 Getter 取值做逻辑判断。若实体结构与表结构差异较大，走 DO + Mapstruct 分离。

#### 值对象与聚合

核心原则：**将隐性的上下文显性化**。多个字段总是同时出现、共同参与同一规则判断、或彼此存在约束关系时，聚合成值对象。

```java
// ❌ 平铺：隐性假设（金额和货币总是成对出现，却只传了一个 BigDecimal）
public void pay(BigDecimal amount, Long recipientId) { ... }

// ✅ 显性化
@Value
public class Money {
    BigDecimal amount;
    Currency currency;

    public Money(BigDecimal amount, Currency currency) {
        notNull(amount);
        notNull(currency);
        this.amount = amount;
        this.currency = currency;
    }
}
```

命名应反映业务含义（`Money` 而非 `AmountAndCurrency`，`TimeRange` 而非 `StartEndPair`）。

#### 状态枚举

```java
public enum TaskStatus {
    PENDING, PROCESSING, SUCCEEDED, FAILED;

    private static final Set<TaskStatus> TERMINAL_STATUS = EnumSet.of(SUCCEEDED, FAILED);
    public boolean checkTerminal() { return TERMINAL_STATUS.contains(this); }
}
```

- 状态判断封装在实体方法中，禁止外部用 `getStatus()` 做 if-else
- 状态值用 enum，禁止 String 散落判断
- **禁止在 domain 和 DO 中使用 `isXxx()` 命名**，统一用 `checkXxx()`、`hasXxx()`、`shouldXxx()`

#### 领域事件

用于**不同业务域之间**的解耦。同域内直接调用即可，不要为解耦而解耦。

命名：`业务动作 + 过去式 + Event`。

```java
public class OtaTaskStatusChangedEvent extends BaseEvent { ... }  // ✅ 反映业务动作
public class OtaTaskSuccessEvent extends BaseEvent { ... }        // ❌ 抽象的"成功"
```

在实体中收集事件，Application 层统一通过 `ApplicationEventPublisher` 发布：

```java
// 实体中收集事件
public class OtaTask {
    @TableField(exist = false)
    private List<BaseEvent> events = new ArrayList<>();

    public void updateStatusBy(OtaTaskStatusCallbackDTO callback) {
        // 更新状态...
        this.events.add(new OtaTaskStatusChangedEvent(this));
    }

    public List<BaseEvent> getEvents() { return events; }
}

// Service 层统一发布
@Service
public class OtaTaskStatusCallbackService {

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void handle(OtaTaskStatusCallbackDTO callback) {
        var task = otaTaskRepository.findByReferenceTaskId(callback.getTaskId());
        task.updateStatusBy(callback);        // 实体中收集事件
        otaTaskRepository.save(task);
        task.getEvents().forEach(event -> applicationEventPublisher.publishEvent(event));
    }
}
```

监听方式：

- 放 `application/listener/` 下，属于对应业务域的 application 层
- **必须**在监听器内通过 Repository 重新查询最新数据（事件发布时数据可能尚未完全持久化，不要用事件携带的过期引用）
- 用 `@Async` + `@EventListener` 实现异步解耦

```java
@Component
public class OtaTaskStatusChangedEventListener {

    @Autowired
    private OtaTaskRepository otaTaskRepository;

    @Autowired
    private RobotService robotService;

    @Async
    @EventListener(OtaTaskStatusChangedEvent.class)
    public void onApplicationEvent(OtaTaskStatusChangedEvent event) {
        // 重新查询最新数据，避免使用过期引用
        var tasks = otaTaskRepository.findByRobotCode(event.getTask().getRobotCode());
        var robot = robotService.findByCode(event.getTask().getRobotCode());
        robotService.updateOtaStatus(robot, tasks);
    }
}
```

#### Repository 接口

定义在 `domain/repository/`，不依赖任何基础设施类；操作的对象是领域实体，不是 DO。

```java
public interface OtaTaskRepository {
    OtaTask save(OtaTask task);
    OtaTask findById(Long id);
    List<OtaTask> findByRobotCode(String robotCode);
}
```

#### 领域服务

业务逻辑涉及**多个领域对象协作**时，抽取到 `domain/service`，而不是在 Application 层编排。

判断依据：

- 判断逻辑只涉及**一个实体自身的字段** → 写在实体方法中
- 判断逻辑涉及**两个及以上实体** → 写在领域服务中

```java
public class OtaUpgradeDomainService {

    public UpgradeResult handle(OtaTask task, Robot robot) {
        task.checkUpgradable();          // 实体自己的行为
        var target = task.resolveVersion();
        robot.markUpgrading(target);     // 另一个实体的行为
        return new UpgradeResult(target, robot.checkUpgrading());
    }
}
```

反模式：

| 反模式 | 后果 | 正确做法 |
| --- | --- | --- |
| domain 引用 infrastructure（注入 Mapper/JpaRepository） | 业务与存储耦合，无法替换存储 | domain 只定义接口（在 `domain/repository`） |
| 外部用 `getStatus()` 做 if-else | 状态判断散落各处，新增状态需改多处 | 实体封装 `checkCompleted()` 等方法 |
| 状态值用 String 散落判断 | 编译期无法检查，拼写错误难发现 | 用 enum |
| 异步监听器直接用 `event.getXxx()` 操作数据 | 使用过期引用，状态扭转失败 | 监听器入口重新查询最新数据 |
| 事件名用 Success/Failed 抽象词 | 无法表达业务动作，排查困难 | 事件名反映业务动作 |
| 构造函数参数超过 4 个 | 调用方难以理解参数含义 | 提取为参数对象 |

### 4.7 infrastructure 层【DDD 强制】

- Repository 实现在 `infrastructure/repository/`，通过 Mapper 操作数据库
- Repository 实现可作为**跨域门面**：本域查询走 Mapper，跨域写用例（建单 / 取消 / 拉起任务流等）在实现类内委托主域应用服务，接口只暴露本域视角的能力（见 2.3 scheduling）
- 数据对象（DO）在 `infrastructure/dataobject/`，与表一一对应
- Mapstruct 在 `infrastructure/mapstruct/`，负责 DO ↔ Domain 转换
- 查询返回 null 或空集合时用 `Optional` / 抛 `NotFoundException`，由具体场景决定
- 回调场景中 `findByXxx` 返回 null 表示不存在（不抛异常），Service 层判断走新建逻辑

```java
@Repository
public class OtaTaskRepositoryImpl implements OtaTaskRepository {

    @Autowired
    private OtaTaskMapper otaTaskMapper;

    private static final OtaTaskMapstruct CONVERTER = OtaTaskMapstruct.INSTANCE;

    @Override
    public OtaTask save(OtaTask task) {
        var dataObject = CONVERTER.toDataObject(task);
        otaTaskMapper.insertOrUpdate(dataObject);
        return CONVERTER.toDomain(dataObject);
    }

    @Override
    public OtaTask findById(Long id) {
        return MybatisPlusUtil.query(otaTaskMapper)
            .eq(OtaTaskDetail::getId, id)
            .eq(OtaTaskDetail::getIsDeleted, 0)
            .one()
            .map(CONVERTER::toDomain)
            .orElseThrow(() -> new NotFoundException(id + " OTA 任务不存在"));
    }
}
```

反模式：

| 反模式 | 后果 | 正确做法 |
| --- | --- | --- |
| infrastructure 包含业务逻辑 | 业务规则散落在持久化层，难以维护 | 业务逻辑下沉到 domain |
| 跨域直接访问对方的 `XxxDO` 或 `XxxMapper` | 领域模型泄漏 | 只跨域访问对方的 domain 实体或 application |
| Repository 中直接使用 DO 而非 domain 对象 | 领域层与存储耦合 | Repository 返回 domain 对象，内部用 Mapstruct 转换 |

---

## 5. 通用约定【强制】

### 5.1 Swagger 注解

所有对外接口必须加 Swagger 注解，方便前端查看接口文档。

```java
// 类级别：@Tag
@Tag(name = "机器人接口")
@RestController
@RequestMapping(APIPath.ROBOT)
public class RobotController { ... }

// 方法级别：@Operation
@PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
@Operation(summary = "创建机器人")
public RobotVO create(@RequestBody @Valid CreateRobotDTO dto) { ... }

// 字段级别：@Schema 替代 Javadoc 字段注释
@Schema(description = "所属分组")
private Long groupId;      // ✅

/** 所属分组 */
private Long groupId;      // ❌
```

### 5.2 RESTful 设计

URL 命名、HTTP 方法选择、正反模式见 `5-restful-api.md`。

### 5.3 异常与日志

见 `3-java-anti-patterns.md` 第 3、4 节。

---

## 6. 建议清单（非强制）

以下是推荐做法。新代码尽量遵守；**存量代码不做红线和强制改造**。

| 建议 | 说明 |
| --- | --- |
| 实体封装行为，不用 setter 拼装 | 创建用构造函数，更新用领域方法 |
| domain 不依赖 infrastructure | domain 定义 Repository 接口，infrastructure 实现 |
| 禁止 `isXxx()` 命名 | 用 `checkXxx()` / `hasXxx()` / `shouldXxx()` |
| 方法不超过 20 行 | 超过则拆成多个小方法 |
| 状态用 enum | 不用 String 散落判断 |
| 参数不超过 4 个 | 超过则封装为参数对象 |
| 字段不超过 30 个、方法不超过 10 个 | 避免"上帝类"，拆值对象或子类 |
| 避免 N+1 查询 | 批量查询代替循环查库 |
| 嵌套 if 不超过 1 层 | 提前 return 或拆子方法 |
| 相同代码多次出现则提取 | 避免改一处漏一处 |
| 用 enum / 值对象替代 primitive | 避免用 String/int 代表业务概念 |

具体反模式与坏味清单见 `3-java-anti-patterns.md`。

---

## 7. 附录

### 7.1 面向对象设计原则

| 原则 | 含义 | 体现 |
| --- | --- | --- |
| 单一职责（SRP） | 一个类只应有一个变更理由 | 实体聚焦自身业务行为；Service / Application 只做编排不做业务判断 |
| 开闭原则（OCP） | 对扩展开放，对修改关闭 | 多考虑接口和抽象，但不要提前过度设计 |
| 接口隔离（ISP） | 接口应小而专 | Repository 按需定义方法，不塞通用 CRUD；Cmd / Query 按用例定义 |
| 依赖倒置（DIP） | 依赖抽象，不依赖具体实现 | domain 定义 Repository 接口，infrastructure 实现 |
| 迪米特法则（LoD） | 不和陌生人说话 | 判断逻辑放在拥有数据的对象中，不在外部用 getter 取值做判断 |

### 7.2 开发前决策清单

- [ ] 本任务涉及哪些包？是沿用现有结构，还是新开业务域（是否需要 DDD 四层，见 2.4）？
- [ ] **业务规则写进 domain 对象了吗？还是散在 Service 里？**
- [ ] 状态判断是否封装成了实体的 `checkXxx()` 方法？
- [ ] 涉及多实体协作时，是否抽了领域服务？
- [ ] 事务边界是否在 Service / Application 方法上，是否覆盖所有跨表写入？
- [ ] 新增接口是否对齐 `5-restful-api.md` 与所在服务 `AGENTS.md` 的本地约定？

### 7.3 修改已有代码的规则

- 修改已有文件时，必须先读取当前代码内容，基于现有代码做增量修改
- 禁止整文件重写（除非文件是全新创建的），用精确替换
- 保留用户已提取的子方法，不能在重写时丢失
- 如果需要调整方法结构，只改动目标部分，不影响其他已有代码
- 禁止把用户手动删除或修改过的代码恢复回去
- 如果发现某个方法/字段不存在，应当假设是用户有意删除的，不要自行加回来
- 如果确实需要该方法才能编译通过，应先询问用户，而不是直接添加
