# 代码注释规范

> **适用范围**：本工作区所有代码（Java 后端、前端、脚本）。
> 由工作区根 `CLAUDE.md` 导入，随会话自动加载。

---

## 1. 不写解释性注释【强制】

**不要往代码里写解释性注释**：动机背景、方案权衡、需求／规则编号、历史沿革、审查结论、以及对代码行为的复述。

这些内容属于实现过程记录，写到需求文档里：

| 内容 | 落点 |
| --- | --- |
| 已定的技术决策、取舍理由 | `requirements/tasks/<任务>.md` 的「已定技术决策」 |
| 审查意见的处理结果、遗留问题 | 同文件的「代码审查处理结果」 |
| 稳定的业务规则、验收标准 | `requirements/specs/<规格>.md` 的编号规则（R/AC） |

```java
// ❌ 解释性注释：动机 + 权衡 + 规则编号，全部属于需求文档
/**
 * 保存（新增与修改共用）：并发写同一名称 + 类型 + 版本组合时前置查询可能都通过，
 * 由数据库唯一索引兜底。这里把唯一索引冲突转成与前置校验相同的业务错误，
 * 使 CI/CD 能据此判断「已存在」并安全重试，而不是收到系统错误（R3）。
 */
private void save(OtaPackage otaPackage) {
    try {
        otaPackageRepository.save(otaPackage);
    } catch (DuplicateKeyException e) {
        throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_ALREADY_EXISTS);
    }
}

// ✅ 方法名、异常类型与错误码本身已经说明一切
private void save(OtaPackage otaPackage) {
    try {
        otaPackageRepository.save(otaPackage);
    } catch (DuplicateKeyException e) {
        throw new BusinessException(RobotManagementErrorCode.OTA_PACKAGE_ALREADY_EXISTS);
    }
}
```

## 2. 允许保留的注释【强制】

只允许**一行以内、指向代码本身看不出的技术陷阱**的注释：

- 框架行为与直觉相反（字段策略、序列化差异、事务传播等）
- 必须满足的外部约束（SQL 拼接白名单、协议字段顺序）
- 无法立即重构的遗留怪写法，及其实际作用

能写进文档的一律写文档；拿不准时**不写**。

## 3. 方法上不写 Javadoc【强制】

方法名、参数名、返回类型和抛出的异常已经说明「做什么」。方法上方的 `/** */` 几乎都在写
「为什么」——动机、权衡、框架行为——那是实现过程记录，按 §1 处理。

对外接口的说明交给 Swagger 注解（`@Operation`），不用 Javadoc。

```java
// ❌ 方法级 Javadoc 在解释动机与框架行为，属于需求文档
/**
 * 转强类型之前先校验结构：Jackson 会把 {@code "robot_sn": 123} 这类值静默强转，
 * 也会让缺失字段以 null 构造出来，畸形报文要在这里挡住。
 */
private CanonicalRobotManifestMessage decode(MessageView view) { ... }

// ✅ 方法名说明做什么，方法体说明怎么做
private CanonicalRobotManifestMessage decode(MessageView view) { ... }
```

方法**内**的一行注释仍按 §2 判断：只有指向代码本身看不出的技术陷阱、且一行能写完时才留。

## 4. 测试代码

`// given` / `// when` / `// then` 阶段标记保留，标记后可补一句简短的状态说明（如 `// given 描述被清空`）；
解释规则依据、框架行为、断言理由的注释同样不写。

## 5. 检查

- 本次改动新增的注释必须符合本规则；存量代码不要求专门回头清理，改到那段代码时顺手删掉。
- 提交前自检：新增的每一行注释，如果删掉后代码行为不会被误解、后来者不会改错，就删掉它。
