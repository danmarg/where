#!/usr/bin/env bash
# Deploys the server by tagging the current origin/main as server-vYYYY.MM.DD.N and pushing the
# tag; the Deploy Server workflow does the actual deploy. Only reviewed code on main can ship.
set -e
set -o pipefail
cd "$(dirname "$0")/.."

git fetch --quiet --tags origin main
sha=$(git rev-parse origin/main)
prefix="server-v$(date -u +%Y.%m.%d)"
n=1
while git rev-parse -q --verify "refs/tags/$prefix.$n" >/dev/null; do n=$((n + 1)); done
tag="$prefix.$n"

echo "Deploying origin/main ($(git log -1 --format='%h %s' "$sha")) as $tag"
read -r -p "Continue? [y/N] " answer
[ "$answer" = "y" ] || [ "$answer" = "Y" ] || exit 1

git tag "$tag" "$sha"
git push origin "refs/tags/$tag"
echo "Pushed $tag. Watch the deploy: gh run list --workflow deploy-server.yml"
