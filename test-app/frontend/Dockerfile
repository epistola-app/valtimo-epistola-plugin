# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2

FROM nginxinc/nginx-unprivileged:1.29-alpine

COPY --chown=1000:1000 ./deployment/dist /usr/share/nginx/html
COPY --chown=1000:1000 .well-known/security.txt /usr/share/nginx/html/.well-known/security.txt
COPY ./conf/default.conf /etc/nginx/conf.d/default.conf
COPY --chmod=0755 ./docker-entrypoint.sh /usr/local/bin/valtimo-frontend-entrypoint.sh

USER 1000

# Renders assets/config.js from the environment and settles the CSP
# frame-ancestors allowlist before starting nginx. See docs/embedding.md.
CMD ["/usr/local/bin/valtimo-frontend-entrypoint.sh"]

EXPOSE 8080
