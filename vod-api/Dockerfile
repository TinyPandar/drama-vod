FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
RUN mkdir -p /root/.m2 && printf '%s\n' \
  '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' \
  > /root/.m2/settings.xml
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/vod-api-0.1.0.jar app.jar
EXPOSE 8001
ENTRYPOINT ["java", "-jar", "app.jar"]
