#!/bin/bash
echo "Which version do you want to release?"
read valtimoReleaseNumber

export VALTIMO_RELEASE_NUMBER=$valtimoReleaseNumber
export VALTIMO_RELEASE_VERSION=${VALTIMO_RELEASE_NUMBER}
export VALTIMO_RELEASE_MAJOR_VERSION=$(awk -F. '{print $1}' <<< ${VALTIMO_RELEASE_NUMBER})

