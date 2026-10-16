# ============ 前端阶段：Node 构建 Vue3 前端 ============
FROM node:20-alpine AS frontend
WORKDIR /web

# 先只拷贝依赖清单安装，利用 Docker 层缓存加速重复构建
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

# 再拷贝源码构建（产出 frontend/dist，vue-tsc 类型检查 + vite 产物）
COPY frontend ./
RUN npm run build

# ============ 构建阶段：用 Maven 镜像编译打包 ============
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷贝 parent + 各模块 pom 下载依赖，利用 Docker 层缓存加速重复构建
COPY pom.xml .
COPY robot-core/pom.xml robot-core/
COPY platform/pom.xml platform/
COPY rms/pom.xml rms/
COPY app/pom.xml app/
RUN mvn -B dependency:go-offline

# 再拷贝各模块源码（源码变更时不会重新下载依赖）
COPY robot-core/src robot-core/src
COPY platform/src platform/src
COPY rms/src rms/src
COPY app/src app/src

# 前端产物替换占位 static（镜像自带完整 UI，8083 直接可访问）
RUN rm -rf app/src/main/resources/static/*
COPY --from=frontend /web/dist ./app/src/main/resources/static
RUN mvn -B package -DskipTests

# ============ 运行阶段：只需 JRE，镜像更小 ============
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 用户运行，更安全
RUN groupadd -r spring && useradd -r -g spring spring \
    && mkdir -p /app/logs && chown -R spring:spring /app

COPY --from=build /build/app/target/*.jar app.jar

# 容器内 JVM 按内存百分比自适应堆大小
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai"

EXPOSE 8083

USER spring
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
