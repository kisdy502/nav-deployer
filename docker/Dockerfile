# ============ 构建阶段：用 Maven 镜像编译打包 ============
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只拷贝 pom.xml 下载依赖，利用 Docker 层缓存加速重复构建
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再拷贝源码打包（源码变更时不会重新下载依赖）
COPY src ./src
RUN mvn -B package -DskipTests

# ============ 运行阶段：只需 JRE，镜像更小 ============
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 用户运行，更安全
RUN groupadd -r spring && useradd -r -g spring spring

COPY --from=build /build/target/*.jar app.jar

# 容器内 JVM 按内存百分比自适应堆大小
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai"

EXPOSE 8083

USER spring
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
