#!/usr/bin/env bash
set -e
: ${2:? Usage: $0 SOURCE_IMAGE TARGET_REPO}
SOURCE_IMAGE="$1"
TARGET_REPO="$2"

echo "CIRCLE_TAG: $CIRCLE_TAG"
echo "CIRCLE_BRANCH: $CIRCLE_BRANCH"

if [[ -n "$CIRCLE_TAG" ]]; then
  TARGET_TAG="$CIRCLE_TAG"
elif [[ "$CIRCLE_BRANCH" == 'master' ]]; then
  TARGET_TAG='latest'
else
  echo "Running in a feature branch. Not releasing."
  exit 0
fi

set -ux

docker tag "$SOURCE_IMAGE" "$TARGET_REPO:$TARGET_TAG"
docker push "$TARGET_REPO:$TARGET_TAG"
