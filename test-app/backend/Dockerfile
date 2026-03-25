FROM eclipse-temurin:21-jre-noble

ADD build/libs/backend-*.war /app.jar

USER 1000

ENTRYPOINT ["java", "-jar", "/app.jar"]
