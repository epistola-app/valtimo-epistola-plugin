# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

FROM eclipse-temurin:21-jre-noble

ADD build/libs/backend-*.war /app.jar

# Java4Shell (CVE-2026-114938): OpenJDK from 17.0.6 has remote code execution through the AVX-512
# Base64 decoder intrinsic. Keep it off until the base image ships a fixed JDK (Temurin 21 was still
# on 21.0.13+9 on 2026-11-19). A deployment that sets JAVA_TOOL_OPTIONS itself must keep this flag.
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"

USER 1000

ENTRYPOINT ["java", "-jar", "/app.jar"]
