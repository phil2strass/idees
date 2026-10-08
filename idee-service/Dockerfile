FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY idee-service/pom.xml ./pom.xml
RUN mvn -q dependency:go-offline
COPY idee-service/src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-dateutil curl && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 --create-home idee
WORKDIR /app
COPY --chmod=0644 --from=build /build/target/idee-service-0.0.1-SNAPSHOT.jar /app/service.jar
COPY --chmod=0755 scripts/project_calendar.py scripts/expand_calendar_json.py /app/scripts/
USER 10001
ENV SERVER_ADDRESS=0.0.0.0 IDEE_CALENDAR_SCRIPT=/app/scripts/expand_calendar_json.py
EXPOSE 8087
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s CMD curl -fsS http://127.0.0.1:8087/api/health || exit 1
ENTRYPOINT ["java","-jar","/app/service.jar"]
