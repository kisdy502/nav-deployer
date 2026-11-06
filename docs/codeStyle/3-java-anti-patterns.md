# Java 反模式与红线清单

> 本文件汇总 Java 后端开发中常见的反模式、红线规则和禁止事项，供日常开发和 Review 时快速查阅。
> 各条目的约束力以 `2-java-backend.md` 的标记为准：
>
> - **【强制】** 业务逻辑必须写进 domain 对象 —— 全工作区所有 Java 后端包适用
> - **【DDD 强制】** 仅在 DDD 四层包内生效（`robot.ota`、`quality.scheduling`）
> - **【建议】** 推荐做法，新代码尽量遵守；**存量代码不做改造要求**
>
> 条目与规范主体的对应关系见各节开头的引用。

---

## 1. 业务逻辑归属【强制】

**这是唯一跨包强制的红线**，详见 `2-java-backend.md` 第 3 节。

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| Service / Controller 里取平铺字段做判断和计算 | 业务规则散落各处，改一处漏一处 | 判断和计算封装进拥有数据的对象 |
| 外部用 `getStatus()` 做 if-else 判断状态 | 状态判断散落各处，新增状态需改多处 | 实体封装 `checkCompleted()`、`checkProcessing()` 等方法 |
| 在编排层做跨实体的业务判断 | 业务规则跑到了 Service / Application 层 | 抽到领域服务（`domain/service`） |
| 状态值用 String 散落判断 | 编译期无法检查，拼写错误难发现 | 用 enum 定义状态 |

```java
// 实体中封装判断方法
public class RobotTask {
    private TaskStatus status;

    public boolean checkCompleted() {
        return status != null && status.checkTerminal();
    }

    public boolean checkProcessing() {
        return status != null && Objects.equals(TaskStatus.PROCESSING, status);
    }
}

// 外部使用
if (task.checkCompleted()) { ... }                     // ✅
if (task.getStatus() == TaskStatus.SUCCEEDED) { ... }  // ❌
```

> 存量代码里 `getStatus()` 判空、字符串比较的写法**不要求回头改**；新写的判断请走实体方法。

---

## 2. 分层与依赖【DDD 强制】

> 只在 DDD 四层包内生效（`robot.ota`、`quality.scheduling`，对应 `2-java-backend.md` 第 4 章）。
> 传统分层的包不受此节约束——传统结构下 Service 直接注入 Mapper 是现状与正常写法。

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| `domain` 引用 `infrastructure`（domain 直接注入 Mapper / JpaRepository） | 业务代码与存储耦合，无法替换存储 | domain 只定义接口（在 `domain/repository/`） |
| `application` 直接判断业务规则（if 状态分支） | 业务规则散落多处 | 单实体判断下沉到实体方法；跨实体判断抽到领域服务 |
| `interfaces` 写业务逻辑（if 分支、对象转换、状态变更） | 控制器膨胀 | 仅做参数校验 + 调用 application |
| `application` 直接调用 Mapper | 绕过 Repository，事务和审计失效 | 通过 Repository 接口操作数据 |
| 跨域直接访问对方的 `XxxDO` 或 `XxxMapper` | 领域模型泄漏 | 只跨域访问对方的 domain 实体或 application |

命名上，DDD 四层包内禁止在 domain 和 DO 中使用 `isXxx()` 命名，统一用 `checkXxx()`、`hasXxx()`、`shouldXxx()` 替代。

---

## 3. 异常处理【建议】

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| Controller 中 try-catch 后吞掉异常 | 问题无法追踪，调用方无感知 | 抛业务异常，由全局异常处理器统一处理 |
| 抛裸 `RuntimeException` | 无法区分业务异常和系统异常 | 业务异常继承自定义 `BusinessException` |
| 用异常控制业务流程 | 性能差，代码可读性低 | 用返回值或状态判断 |

---

## 4. 日志【建议】

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| 使用 `System.out.println` | 日志无法配置、收集和检索 | 使用 SLF4J + Logback |
| 使用 `printStackTrace` | 输出到 stderr，无法统一管理 | 使用 `log.error("msg", e)` |
| 字符串拼接日志 | 无论是否打印都拼接字符串，性能浪费 | 使用占位符 `log.info("msg: {}", val)` |

---

## 5. 常见代码坏味【建议】

| 反模式 | 后果 | 正确做法 |
|--------|------|---------|
| for 循环中逐条查数据库（N+1 Query） | 数据库交互次数剧增，性能严重下降 | 批量查询代替循环查 |
| if 嵌套超过 1 层（Nested If） | 逻辑难读，维护成本高 | 提前 return 或拆分子方法 |
| 相同或高度相似代码多次出现（Duplicated Code） | 修改容易遗漏，一处改多处漏 | 提取公共方法或抽象 |
| 方法超过 20 行承担太多逻辑（Long Method） | 难理解、难测试、难复用 | 拆多个小方法，每个聚焦单一职责 |
| 字段 > 30 个或方法 > 10 个（Large Class） | 类职责过多，变成"上帝类" | 拆值对象或拆分子类 |
| 参数超过 4 个（Long Parameter List） | 调用方难理解，容易传错 | 封装为参数对象；调用方手上已有完整对象时直接传它，别拆成散字段 |
| 一组字段/参数经常一起出现（Data Clumps） | 散落传递，缺少业务语义 | 封装成独立值对象（如 `TimeRange`） |
| 用 String/int 代表业务概念（Primitive Obsession） | 类型不安全，缺少行为封装 | 用 enum 或值对象替代（如 `Money`） |
| 大量 if/switch 按类型分支判断（Switch Statements） | 新增类型需改多处，违反开闭原则 | 用多态或策略模式替代 |
| 连续调用多个对象 `a.getB().getC().getD()`（Message Chains） | 耦合度高，中间对象变化则全链断裂 | 遵循最少知道原则，在拥有数据的对象中封装方法 |
| 两个类过度依赖对方内部细节（Inappropriate Intimacy） | 耦合过紧，一个类变化影响另一个 | 通过接口隔离，减少耦合 |
| 三元表达式（Ternary Expressions） | 分支语义被压进表达式，不利于阅读 | 一律用 if-else 显式分支 |

```java
// ❌ 三元里做流程分支：按站模式分流压成一行，语义藏在表达式里
var result = station.checkAutoMode()
        ? openBatchFromCallBox(normalizedStationCode)
        : createFromCallBox(buildUiCreateDTO(station));

// ✅ if-else：分支即流程，一眼可读
CallBoxWorkOrderCreationResult result;
if (station.checkAutoMode()) {
    result = openBatchFromCallBox(normalizedStationCode);
} else {
    result = createFromCallBox(buildUiCreateDTO(station));
}

// ❌ 返回值语义随 null 切换：返回的是批次号还是工单 ID，全靠读实现猜
return result.batchNo() != null ? result.batchNo() : String.valueOf(result.workOrderId());

// ✅ 两个分支各返回什么，明说
if (result.batchNo() != null) {
    return result.batchNo();
}
return String.valueOf(result.workOrderId());
```
