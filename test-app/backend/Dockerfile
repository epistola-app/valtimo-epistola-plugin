# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

FROM eclipse-temurin:21-jre-noble

ADD build/libs/backend-*.war /app.jar

USER 1000

ENTRYPOINT ["java", "-jar", "/app.jar"]
