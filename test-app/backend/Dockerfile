FROM eclipse-temurin:21-jre-noble

RUN useradd --uid 1000 --gid 1000 --no-create-home app

ADD build/libs/backend-*.war /app.jar

USER 1000

ENTRYPOINT ["java", "-jar", "/app.jar"]
