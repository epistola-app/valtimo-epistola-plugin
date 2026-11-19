# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

FROM nginxinc/nginx-unprivileged:1.31-alpine

# Take Alpine's security fixes at build time instead of waiting for the next base image: on
# 2026-11-19 the base still shipped brotli, musl, expat, libxslt, curl and zlib with fixed
# releases available.
USER root
RUN apk upgrade --no-cache

COPY --chown=1000:1000 ./deployment/dist /usr/share/nginx/html
COPY --chown=1000:1000 .well-known/security.txt /usr/share/nginx/html/.well-known/security.txt
COPY ./conf/default.conf /etc/nginx/conf.d/default.conf
COPY --chmod=0755 ./docker-entrypoint.sh /usr/local/bin/valtimo-frontend-entrypoint.sh

USER 1000

# Renders assets/config.js from the environment and settles the CSP
# frame-ancestors allowlist before starting nginx. See docs/embedding.md.
CMD ["/usr/local/bin/valtimo-frontend-entrypoint.sh"]

EXPOSE 8080
