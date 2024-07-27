FROM gradle:8.9.0-jdk17-alpine

WORKDIR /home/gradle/world-host-server
ADD . .

RUN gradle build
RUN mv build/libs/world-host-server-kotlin-*-fat.jar build/libs/world-host-server.jar

FROM openjdk:17-slim

WORKDIR /usr/world-host-server
COPY --from=0 /home/gradle/world-host-server/build/libs/world-host-server.jar .

CMD ["java", "-jar", "world-host-server.jar"]
