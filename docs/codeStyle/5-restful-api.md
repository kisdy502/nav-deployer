# RESTful API 设计规范

> 适用范围：`business-group/backend-group/*` 下的所有对外 HTTP 接口。
> 各服务 `AGENTS.md` 的本地约定优先于本规范（例如 `robot-management-service/AGENTS.md` 第 9 节的路径前缀、字段命名、响应结构约定）。

---

## 1. 什么是 RESTful

RESTful 是一种 API 设计风格，核心思想是**将业务能力抽象为资源**，通过 HTTP 方法对资源进行操作。

**核心原则**：
- **面向资源**：URL 表示"资源是什么"，而不是"资源做什么"
- **HTTP 方法表达动作**：`GET` 查询、`POST` 创建、`PUT/PATCH` 更新、`DELETE` 删除
- **无状态**：每次请求携带所有必要信息，不依赖服务端 session

### 对比示例

```
# ❌ 非 RESTful（面向动作）
POST /api/v1/createRobot
POST /api/v1/deleteRobotById
POST /api/v1/updateRobot
GET  /api/v1/getRobotList

# ✅ RESTful（面向资源）
POST   /api/v1/robot-management/robots      # 创建
DELETE /api/v1/robot-management/robots/{id} # 删除
PUT    /api/v1/robot-management/robots/{id} # 更新
GET    /api/v1/robot-management/robots      # 查询列表
```

---

## 2. 资源命名规则

### 2.1 基础路径

所有对外接口以 `/api/v1` 开头，不同业务域使用独立路径前缀。

管理类接口统一使用 `/api/v1/{service}-management/...`，例如 `/api/v1/robot-management/...`。

### 2.2 命名规则

| 规则 | ✅ 正确 | ❌ 错误 |
|------|---------|--------|
| 使用**名词**，表达资源含义 | `/robots`、`/robot-groups` | `/create-robot`、`/get-robot-list` |
| 多单词用**连字符（-）**分隔 | `/robot-management/robot-groups` | `/robotManagement/robotGroups`、`/robot_groups` |
| 不使用动词（动作由 HTTP 方法表达） | `/robots`（创建用 POST） | `/create-robot`、`/delete-robot` |
| 路径统一小写 | `/robot-groups` | `/Robot-Groups` |

> **关于单复数**：业界没有统一标准——复数派认为 `GET /robots` 返回"多个机器人"语义更通顺，单数派更简洁且免去不规则复数困扰（如 `status` → `statuses`）。两种风格都正确，**关键是同一服务内保持一致**。
>
> 各服务现状以复数为主（`robots`、`robot-groups`、`robot-types`、`robot-parts`），**沿用现状即可**，不要为了统一单复数发起重构。

### 2.3 URL 层级

```
# 资源集合 / 单个资源
GET    /api/v1/{prefix}/{resource}               # 查询资源列表
POST   /api/v1/{prefix}/{resource}               # 创建资源
GET    /api/v1/{prefix}/{resource}/{id}          # 查询单个资源
PUT    /api/v1/{prefix}/{resource}/{id}          # 全量更新
PATCH  /api/v1/{prefix}/{resource}/{id}          # 部分更新
DELETE /api/v1/{prefix}/{resource}/{id}          # 删除资源

# 子资源（层级关系明确时使用，不建议超过 2 层）
GET    /api/v1/{prefix}/{resource}/{id}/{subResource}
```

---

## 3. HTTP 方法使用

| 方法 | 用途 | 幂等 | 说明 |
|------|------|------|------|
| `GET` | 查询资源 | 是 | 不改变服务器状态，可重复调用 |
| `POST` | 创建资源 / 触发动作 | 否 | 每次调用可能产生不同结果 |
| `PUT` | 全量替换资源 | 是 | 必须提供完整资源，缺失字段视为清空 |
| `PATCH` | 部分更新资源 | 是 | 只提供需要更新的字段 |
| `DELETE` | 删除资源 | 是 | 多次调用结果一致 |

---

## 4. 正反模式

### 4.1 URL 命名

```java
// ✅ 正确：面向资源，名词，连字符分隔
/api/v1/robot-management/robots
/api/v1/robot-management/robot-types
/api/v1/robot-management/robot-groups
/api/v1/robot-management/robot-parts
/api/v1/robot-registration

// ❌ 禁止：面向动作，动词出现在 URL 中
/api/v1/createRobot
/api/v1/deleteRobotById
/api/v1/getRobotList

// ❌ 禁止：错误命名风格
/api/v1/robotManagement      // 驼峰
/api/v1/robot_management     // 下划线
/api/v1/Robot-Management     // 大写
/api/v1/create-robot         // 动词
```

### 4.2 HTTP 方法选择

```java
// ✅ 正确：查询用 GET，创建用 POST，删除用 DELETE
@GetMapping("/{id}")                            // 查询单个
@GetMapping                                     // 查询列表
@PostMapping                                    // 创建
@PutMapping("/{id}")                            // 全量更新
@PatchMapping("/{id}")                          // 部分更新
@DeleteMapping("/{id}")                         // 删除

// ❌ 禁止：所有操作都用 POST
@PostMapping("/create")                         // 不该有动作词
@PostMapping("/delete")
@PostMapping("/update")

// ❌ 禁止：GET 请求修改数据
@GetMapping("/delete/{id}")                     // 应该用 DELETE
@GetMapping("/update-actionStatus")                   // 应该用 PUT/PATCH
```

### 4.3 子资源 vs 查询参数

```java
// ✅ 正确：子资源表示层级关系
/api/v1/robot-management/robots/zenoh
/api/v1/robot-management/robots/{id}/parts

// ✅ 正确：查询参数表示过滤条件
/api/v1/robot-management/robots?status=ONLINE

// ❌ 禁止：用查询参数取代路径层级
/api/v1/parts?robotId=123
```

---

## 5. 响应约定

请求/响应体的字段命名、响应结构、ID 类型转换等约定，以**各服务 `AGENTS.md` 的 Local Overrides 一节**为准。

以 `robot-management-service` 为例：

- JSON 字段统一 `snake_case`
- 对外响应统一使用 `status / msg / data`
- 雪花 ID 在后端内部保持 `Long`，对前端输出统一为 `String`
