#!/bin/bash
git fetch && git checkout v/${VALTIMO_RELEASE_MAJOR_VERSION} && git pull
sed -r -i '' 's/"@valtimo\/([a-z0-9_-]+)".+/"@valtimo\/\1": "'${VALTIMO_RELEASE_VERSION}'",/' package.json
npm i
git add package.json && git add package-lock.json && git commit -m "Upgrade to ${VALTIMO_RELEASE_VERSION}" && git push
