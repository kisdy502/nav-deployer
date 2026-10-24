# Docker 打包与部署

更新日期：2026-10-22。对应根目录 `docker-compose.yml`、`deploy.sh` 和 `docker/Dockerfile`。

## 快速启动与 build / up 区别

Docker Desktop 需使用 Linux 容器模式。所有命令在仓库根目录执行；Windows 可使用 Git Bash，
PowerShell 可直接使用下面的等价 Compose 命令。

```bash
docker compose config --quiet
bash deploy.sh up
# 等价命令：docker compose up -d --build
```

| 命令 | 实际作用 |
|---|---|
| `bash deploy.sh build` | 执行 `docker compose build app`，只构建上位机镜像，不启动容器 |
| `bash deploy.sh up` | 执行 `docker compose up -d --build`，构建应用并后台启动或更新 postgres、redis、minio、app |
| `bash deploy.sh logs` | 跟随上位机日志 |
| `bash deploy.sh down` | 停止并移除本项目容器和默认网络，保留命名数据卷及外部机器人网络 |

`up` 已包含构建，通常不必先 `build`。分两步用于先确认构建成功，第二步会复用缓存。
已单独构建成功时，也可执行 `docker compose up -d --no-build` 直接启动。

镜像使用 Node 22 执行 `npm ci`、TypeScript 检查和 Vite 构建；使用 Maven / Java 21
编译四个后端模块，将前端产物嵌入 Spring Boot 静态资源；最终使用 Ubuntu Noble 的 Java 21 JRE。
8083 同时提供前端、API 和 SSE，无需额外部署 Nginx。
镜像打包使用 `-DskipTests`，构建成功不代表测试已运行。首次下载基础镜像及 npm / Maven 依赖可能较慢。

## 机器人网络

先启动仿真机器人，再启动上位机。上位机默认加入已有的
`simulated_chassis_agv_network` 网络，通过 `ws://agv-bridge:9090` 连接机器人，
通过 `http://agv-bridge:8089` 访问 3D 地图服务。机器人无需向宿主机发布端口供上位机使用。
浏览器通过上位机 API 访问机器人，不需要解析 `agv-bridge`。

```bash
docker network inspect simulated_chassis_agv_network
```

该命令只检查网络是否存在和其中的容器，不会创建或修改网络。网络由机器人项目创建，
提示不存在时先启动机器人或设置实际网络名。确认后无需每次执行。
上位机启动后，网络中应出现 `nav-deployer-app`；同时它仍在项目默认网络连接数据库等服务。

访问 http://localhost:8083 。`build` 和 `up` 使用相同的 Compose 镜像标签。
`up` 会构建并启动全部服务；已有数据库和对象存储数据卷会保留。

## 根目录 .env

没有 `.env` 时手动新建，与 `docker-compose.yml` 同级；已有文件时按需修改并保留其他配置。
Compose 自动读取它用于变量替换，无需添加 `env_file`。建议配置如下：

```dotenv
ROBOT_DOCKER_NETWORK=simulated_chassis_agv_network
SIM_AGV_WS_URL=ws://agv-bridge:9090
SIM_AGV_TF_CHILD_FRAME=AGV002/base_link
# 可选；留空会从 WebSocket 地址推导 HTTP 8089
MAP3D_BASE_URL=
APP_IMAGE=nav-deployer:v0.2.0

# false：不连接 RMS，仍连接仿真机器人；true：启用 RMS
RMS_ENABLED=false
RMS_ZENOH_CONFIG_FILE=/app/config/zenoh.json5
# 多上位机同时连接 RMS 时设置不同且跨重启稳定的 SN
RMS_ROBOT_SN=
RMS_INSPECTION_IMAGE_DIR=/app/inspection-images
# Windows PowerShell / Git Bash；WSL 内的 Compose 改为 /mnt/c/Users/think/Pictures
INSPECTION_IMAGE_HOST_DIR=C:/Users/think/Pictures
RMS_TEACH_SAMPLE_DIR=
```

`.env` 只影响 Compose 中引用并传入容器的变量，不会自动将任意变量注入应用。
终端已有的同名环境变量可能覆盖 `.env`；用 `docker compose config` 查看最终配置时，
注意输出中包含连接凭据，不要直接对外分享。

外部网络必须事先存在，Compose 不负责启动或停止机器人。若改走宿主机发布端口，
将 `SIM_AGV_WS_URL` 改为 `ws://host.docker.internal:9090`；仍需指定一个存在的机器人网络。
`SIM_AGV_TF_CHILD_FRAME` 必须匹配机器人实际的 `agv_id`；当前运行的机器人为 `AGV002`，
Compose 以此为默认值。机器人切为 `AGV001` 时，应同步改为 `AGV001/base_link`。
机器人侧需实际启动 8089 的 3D 地图服务才能使用点云预览；仅发布 Docker 端口不足以提供该服务。

## Zenoh 配置与质检图片挂载

当前 `app.volumes` 已有以下挂载：

```yaml
    volumes:
      - "app_logs:/app/logs"
      - "./modules/app/src/main/resources/config-json5.json:/app/config/zenoh.json5:ro"
      - type: bind
        source: ${INSPECTION_IMAGE_HOST_DIR:-C:/Users/think/Pictures}
        target: /app/inspection-images
        read_only: true
        bind:
          create_host_path: false
```

左侧是宿主机文件或目录，右侧是容器路径，`:ro` 表示只读。引号让整个路径明确作为字符串，
Windows 路径建议使用 `/`。启动前确认左侧文件、目录实际存在，Docker Desktop 可访问，
且容器运行用户有读取权限。换电脑或部署到 Linux 时，必须改为目标机器的实际路径。

图片目录使用长格式 bind 挂载，避免 Windows 盘符与短格式分隔符混淆。
若在 WSL 内运行 Linux 版 Compose，请在 `.env` 中设置
`INSPECTION_IMAGE_HOST_DIR=/mnt/c/Users/think/Pictures`；Linux 服务器使用自己的绝对路径。
此变量是宿主机目录，`RMS_INSPECTION_IMAGE_DIR=/app/inspection-images` 始终是容器内目录。
`create_host_path: false` 会在目录不存在时报错，避免拼错路径后创建空目录。

若要使用 `config-json5-audi.json`，替换挂载左侧文件名即可，右侧路径与 `.env` 保持一致。
修改文件中的 `connect.endpoints` 切换 Zenoh 服务器，例如：

```json
"connect": {
  "endpoints": ["tcp/192.168.1.20:7447"]
}
```

地址须能从容器内访问；`localhost` 指上位机容器自身。
`RMS_ZENOH_CONFIG_FILE` 留空时读取镜像内置配置，非空时读取指定挂载文件。
当前默认启用 RMS；上述 `.env` 示例显式关闭，需连接时改成 `RMS_ENABLED=true`。

质检代码读取 `RMS_INSPECTION_IMAGE_DIR` 指定的容器目录，因此填写 `/app/inspection-images`，
不要填写 Windows 图片路径。宿主机目录内图片通过挂载提供，不需要打进镜像。
如需 teach 样例，另加只读目录挂载，并将 `RMS_TEACH_SAMPLE_DIR` 设为对应容器路径。

## 启动后检查与局域网访问

```bash
docker compose ps
docker compose logs --tail=100 app
```

确认应用运行、中间件健康，并查看 rosbridge 连接日志。
其他电脑或机器人浏览器访问 `http://宿主机局域网IP:8083`，不能使用对方自己的 `localhost`。
Windows 可用 `ipconfig` 查 IPv4 地址，防火墙需允许 TCP 8083 入站。
访问者看到和控制的仍是上位机配置的机器人，不会自动切换成访问页面的设备。
若页面中的预签名图片链接使用 MinIO 9000，访问者也需要能访问宿主机该端口。

机器人 rosbridge 使用 9090、模型资源使用 8090、3D 地图使用 8089。
同网络通信不要求将这些端口发布到宿主机，但机器人内部必须实际启动对应服务；
仅有端口映射不能证明服务在运行。8089 无监听时，3D 点云预览不可用。

## 修改配置与升级

| 修改内容 | 应用方式 |
|---|---|
| 前端、Java、镜像内资源、Dockerfile | 重新执行 `bash deploy.sh up` |
| `.env`、Compose 环境变量、网络或挂载路径 | 重新创建 app 容器，命令见下方 |
| 挂载的 Zenoh 配置内容 | 重启应用重新读取；重新创建容器还能确保编辑器替换后的文件重新挂载 |
| 已挂载图片目录内新增图片 | 直接可见，无需重新构建镜像 |

中间件已启动时，仅应用配置修改：

```bash
docker compose config --quiet
docker compose up -d --no-deps --force-recreate app
docker compose logs --tail=100 app
```

`docker compose restart app` 不会更新容器环境变量。启停 RMS 时修改 `.env` 的
`RMS_ENABLED=true/false` 后，使用上述重新创建命令。

升级前备份数据库；已有命名数据卷会保留，表结构由 Flyway 迁移。
从 Git 拉取部署时，确认新增前后端文件及 `db/migration` 脚本已提交完整。

不要使用 `docker compose down -v` 更新版本，该命令会删除数据库等持久化数据卷。
