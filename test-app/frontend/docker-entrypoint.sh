#!/bin/sh
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: EUPL-1.2
#
# Container start-up: render the runtime app config, then decide whether this
# app may be framed. See docs/embedding.md.
set -eu

HTML_ROOT=/usr/share/nginx/html
NGINX_CONF=/etc/nginx/conf.d/default.conf

# 1. window['env'], from the environment.
envsubst <"$HTML_ROOT/assets/config.template.js" >"$HTML_ROOT/assets/config.js"

# 2. CSP frame-ancestors.
#
# The conf file already carries 'none' literally, and is only rewritten once a
# usable allowlist has been established. Every failure path below therefore
# leaves the app un-framable rather than framable by anyone: an unset variable,
# a typo'd one, an empty list, or this script not running at all.
frame_ancestors="'none'"

if [ "${EMBEDDING_ENABLED:-false}" = "true" ]; then
  origins=$(printf '%s' "${EMBEDDING_ALLOWED_PARENT_ORIGINS:-}" | tr ',' ' ' | tr -s ' ' |
    sed -e 's/^ *//' -e 's/ *$//')

  if [ -z "$origins" ]; then
    echo "embedding: EMBEDDING_ENABLED=true but EMBEDDING_ALLOWED_PARENT_ORIGINS is empty" >&2
  elif [ -n "$(printf '%s' "$origins" | tr -d 'A-Za-z0-9:/. -')" ]; then
    # Anything outside the character set an origin list can legitimately use.
    # Rejecting the whole list keeps it out of the sed below, where a stray
    # quote or delimiter could otherwise reshape the directive.
    echo "embedding: EMBEDDING_ALLOWED_PARENT_ORIGINS contains unexpected characters" >&2
  else
    frame_ancestors="$origins"
  fi
fi

if [ "$frame_ancestors" != "'none'" ]; then
  sed -i "s|frame-ancestors [^\"]*;|frame-ancestors ${frame_ancestors};|" "$NGINX_CONF"
fi

echo "embedding: Content-Security-Policy frame-ancestors ${frame_ancestors}"

exec nginx -g 'daemon off;'
