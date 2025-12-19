#!/bin/bash
git fetch && git checkout v/${VALTIMO_RELEASE_MAJOR_VERSION} && git pull
sed -i '' '/valtimoVersion/d' ./gradle.properties && echo "valtimoVersion=${VALTIMO_RELEASE_VERSION}" >> ./gradle.properties
git add gradle.properties && git commit -m "Upgrade to ${VALTIMO_RELEASE_VERSION}" && git push

