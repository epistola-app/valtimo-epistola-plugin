# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

FROM eclipse-temurin:21-jre-noble

# The base image trails Ubuntu's security updates between its own rebuilds, and the image scan
# reports only what already has a fix. Take those fixes at build time.
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*

ADD build/libs/backend-*.war /app.jar

USER 1000

ENTRYPOINT ["java", "-jar", "/app.jar"]
