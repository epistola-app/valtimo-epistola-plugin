FROM nginxinc/nginx-unprivileged:1.28-alpine

COPY --chown=1000:1000 ./deployment/dist /usr/share/nginx/html
COPY --chown=1000:1000 .well-known/security.txt /usr/share/nginx/html/.well-known/security.txt
COPY ./conf/default.conf /etc/nginx/conf.d/default.conf

USER 1000

# When the container starts, replace the env.js with values from environment variables
CMD ["/bin/sh",  "-c",  "envsubst < /usr/share/nginx/html/assets/config.template.js > /usr/share/nginx/html/assets/config.js && exec nginx -g 'daemon off;'"]

EXPOSE 8080
