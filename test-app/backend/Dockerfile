FROM eclipse-temurin:21-jre-noble

RUN groupadd --gid 1000 app && \
    useradd --uid 1000 --gid app --no-create-home app

ADD build/libs/backend-*.war /app.jar

USER 1000

ENTRYPOINT ["java", "-jar", "/app.jar"]
