# 构建阶段
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app

# 依赖下载走阿里云公共仓库，避免国内网络访问 Maven Central 超时
COPY deploy/maven-settings.xml /opt/maven/aliyun-settings.xml

COPY pom.xml .
COPY src ./src
# BuildKit 缓存卷复用 ~/.m2，重复构建无需重新下载全部依赖
RUN --mount=type=cache,target=/root/.m2 mvn -B -s /opt/maven/aliyun-settings.xml clean package -DskipTests

# 运行阶段仅保留 JRE 和应用产物，减小镜像体积
FROM amazoncorretto:21-alpine
WORKDIR /app
RUN addgroup -S agent && adduser -S agent -G agent
COPY --from=build /app/target/travelmind-ai.jar /app/travelmind-ai.jar
RUN chown -R agent:agent /app

USER agent
EXPOSE 8123 9090
ENTRYPOINT ["java", "-jar", "/app/travelmind-ai.jar"]
CMD ["--spring.profiles.active=prod"]
