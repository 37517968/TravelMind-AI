# 构建阶段
FROM maven:3.9-amazoncorretto-21 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

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
