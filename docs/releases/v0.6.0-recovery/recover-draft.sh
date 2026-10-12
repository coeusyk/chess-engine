#!/usr/bin/env bash
# Complete the existing v0.6.0 DRAFT from the preserved build artifact. Does not publish.
# Safe to rerun: uploads use --clobber on three fixed names, every step re-verifies first.
# Usage: NOTES=<release-body.md> bash recover-v0.6.0-draft.sh
set -euo pipefail

export GH_REPO=coeusyk/chess-engine
TAG=v0.6.0
SHA=bc3dc380e759526e8aa19cd8385af16ab22ac502
RUN=38221726307
ARTIFACT=vex-release-38221726307-1
ARTIFACT_ID=11734169384
ARTIFACT_DIGEST=sha256:2d867527985c90779e50c92fb068223693d28af8001d4b4e93809815d5b2410f
RELEASE_ID=410354522
JAR_SHA=e16163b8fe6f8278161db0d9220883c5d49a4814df0d81f7a6cad901c07cf4e7
: "${NOTES:?set NOTES to the reviewed release body file}"
die() { echo "ABORT: $*" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Called at the start and again immediately before every write, so a release published
# mid-run is never modified (--clobber and --draft would otherwise alter a public release).
require_expected_draft() {
  [[ $(git ls-remote origin "refs/tags/$TAG^{}" | cut -f1) == "$SHA" ]] || die "tag no longer peels to $SHA"
  gh api "repos/$GH_REPO/releases/$RELEASE_ID" --jq '[.draft,.tag_name,.target_commitish]|@tsv' \
    | grep -qx "true	$TAG	$SHA" || die "release $RELEASE_ID is not the expected draft"
}

echo "1. tag and draft identity"
require_expected_draft

echo "2. preserved artifact is still there and unexpired"
gh api "repos/$GH_REPO/actions/artifacts/$ARTIFACT_ID" --jq '[.name,.expired,.workflow_run.id,.digest]|@tsv' \
  | grep -qx "$ARTIFACT	false	$RUN	$ARTIFACT_DIGEST" || die "artifact $ARTIFACT_ID is missing, expired, or not the original; do not rebuild, ask the maintainer"

echo "3. download and verify the original bundle"
gh run download "$RUN" -n "$ARTIFACT" -D "$WORK/bundle"
( cd "$WORK/bundle"
  [[ $(ls | sort | tr '\n' ' ') == "engine-uci-0.6.0.jar engine-uci-0.6.0.jar.sha256 release-manifest-0.6.0.json release-notes.md " ]] \
    || die "unexpected bundle contents"
  sha256sum -c engine-uci-0.6.0.jar.sha256
  [[ $(sha256sum engine-uci-0.6.0.jar | cut -d' ' -f1) == "$JAR_SHA" ]] || die "JAR hash differs from the frozen value"
  python3 -I - "$JAR_SHA" "$SHA" <<'PY'
import json, sys
m = json.load(open("release-manifest-0.6.0.json"))
want = {"artifact": "engine-uci-0.6.0.jar", "minimum_java_version": 21, "sha256": sys.argv[1],
        "source_commit": sys.argv[2], "tag": "v0.6.0", "version": "0.6.0"}
assert m == want, m
PY
)

echo "4. upload exactly three assets, then set the reviewed body (release stays a draft)"
require_expected_draft
unexpected=$(gh api "repos/$GH_REPO/releases/$RELEASE_ID/assets" --jq '.[].name' \
  | grep -vxE 'engine-uci-0.6.0.jar|engine-uci-0.6.0.jar.sha256|release-manifest-0.6.0.json' || true)
[[ -z $unexpected ]] || die "draft already holds unexpected assets: $unexpected"
( cd "$WORK/bundle" && gh release upload "$TAG" engine-uci-0.6.0.jar engine-uci-0.6.0.jar.sha256 release-manifest-0.6.0.json --clobber )
require_expected_draft
gh release edit "$TAG" --notes-file "$NOTES" --draft

echo "5. verify what GitHub now holds"
gh api "repos/$GH_REPO/releases/$RELEASE_ID" --jq '[.draft,.tag_name]|@tsv' | grep -qx "true	$TAG" || die "draft state changed"
gh api "repos/$GH_REPO/releases/$RELEASE_ID/assets" --jq '[.[]|select(.state=="uploaded")|.name]|sort|join(" ")' \
  | grep -qx "engine-uci-0.6.0.jar engine-uci-0.6.0.jar.sha256 release-manifest-0.6.0.json" || die "asset set is not exactly the three expected files"
mkdir "$WORK/remote"
gh release download "$TAG" -D "$WORK/remote"
( cd "$WORK/remote" && sha256sum -c engine-uci-0.6.0.jar.sha256 && cmp engine-uci-0.6.0.jar "$WORK/bundle/engine-uci-0.6.0.jar" \
  && cmp release-manifest-0.6.0.json "$WORK/bundle/release-manifest-0.6.0.json" && cmp engine-uci-0.6.0.jar.sha256 "$WORK/bundle/engine-uci-0.6.0.jar.sha256" )
[[ $(git ls-remote origin "refs/tags/$TAG^{}" | cut -f1) == "$SHA" ]] || die "tag moved during recovery"
echo "OK: draft $RELEASE_ID holds the verified assets. Not published."
