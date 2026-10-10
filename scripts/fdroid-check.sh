#!/usr/bin/env bash
# Build the app the way F-Droid will, and check it matches our signed APK.
#
#   ./scripts/fdroid-check.sh            the commit checked out (HEAD)
#   ./scripts/fdroid-check.sh v0.1.0     a tag or any pushed commit
#
# F-Droid publishes our own signed APK only if its build server, building the
# same commit from source, produces the same APK byte for byte, signature
# aside. This runs that build in F-Droid's own build image, with fdroidserver
# set up as fdroiddata's CI sets it up, then:
#
#   1. fdroid lint on our metadata draft (docs/fdroid/);
#   2. fdroid build of the commit, from GitHub — so it must be pushed;
#   3. fdroid's own comparison of that build with our signed release APK,
#      app/build/outputs/apk/release/app-release.apk, which must have been
#      built from the same commit (./scripts/build.sh release).
#
# Worth running before tagging: a difference found here costs nothing, while
# one found after the tag costs a version number.
#
# Needs podman and network access. The first run pulls a large image and
# downloads Gradle and the build's dependencies inside it; work files go to
# build/fdroid-check/, which is git-ignored.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly APPLICATION_ID="io.github.molleware.porygonlist"
readonly IMAGE="registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie"
readonly DRAFT="$REPO_ROOT/docs/fdroid/$APPLICATION_ID.yml"
readonly SIGNED_APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"
readonly WORK="$REPO_ROOT/build/fdroid-check"

command -v podman >/dev/null || die "podman is not installed."

ref="${1:-HEAD}"
commit="$(git -C "$REPO_ROOT" rev-parse --verify "$ref^{commit}")" || die "No such commit: $ref"
git -C "$REPO_ROOT" branch -r --contains "$commit" | grep -q . \
  || die "$ref is not pushed. F-Droid builds from GitHub, so push it first."

if [[ -f "$SIGNED_APK" ]]; then
  warn "Comparing against ${SIGNED_APK#"$REPO_ROOT"/}, built $(date -r "$SIGNED_APK" '+%F %R'). It must come from $ref (${commit:0:7})."
else
  warn "No signed release APK, so this only lints and builds. To compare too: ./scripts/build.sh release"
fi

info "Preparing ${WORK#"$REPO_ROOT"/} for ${commit:0:7}"
rm -rf "$WORK"
mkdir -p "$WORK/metadata" "$WORK/compare"
# The draft as it will be submitted, except that it builds this commit rather
# than the tag, and does not fetch Binaries: before the release exists that
# URL is a 404, and the comparison below does that job against the local APK.
sed -e "s|^    commit: .*|    commit: $commit|" -e '/^Binaries:/d' "$DRAFT" >"$WORK/metadata/$APPLICATION_ID.yml"
[[ -f "$SIGNED_APK" ]] && cp "$SIGNED_APK" "$WORK/compare/signed.apk"
printf 'sdk_path: /opt/android-sdk\n' >"$WORK/config.yml"
chmod 600 "$WORK/config.yml"

info "Running in $IMAGE"
podman run --rm \
  -v "$WORK:/data:Z" \
  -w /data \
  -e APPLICATION_ID="$APPLICATION_ID" \
  "$IMAGE" \
  bash -euo pipefail -c '
    export ANDROID_HOME=/opt/android-sdk
    test -n "${fdroidserver:-}" || source /etc/profile.d/bsenv.sh
    git clone --quiet --depth 1 https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
    export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver:$fdroidserver/examples" PYTHONUNBUFFERED=true
    echo "fdroidserver $(git -C "$fdroidserver" log -1 --format="%h %cs")"

    echo; echo "==> fdroid lint"
    fdroid lint "$APPLICATION_ID"

    echo; echo "==> fdroid build"
    fdroid build --verbose --test --on-server --no-tarball "$APPLICATION_ID"

    unsigned="$(ls tmp/${APPLICATION_ID}_*.apk | head -1)"
    cp "$unsigned" compare/fdroid-unsigned.apk
    if [[ ! -f compare/signed.apk ]]; then
      echo; echo "Built. Nothing to compare with yet."
      exit 0
    fi

    echo; echo "==> Comparing with our signed APK"
    python3 - "$unsigned" <<"EOF"
import sys
from fdroidserver import common
common.config = common.read_config()
problem = common.verify_apks("compare/signed.apk", sys.argv[1], "compare")
if problem:
    print("DIFFERENT:", problem)
    sys.exit(1)
print("IDENTICAL: F-Droid would publish our signed APK.")
EOF
  '

if [[ -f "$WORK/compare/signed.apk" ]]; then
  info "Reproducible. F-Droid's unsigned build is in ${WORK#"$REPO_ROOT"/}/compare/."
else
  info "Lint and build passed. F-Droid's unsigned build is in ${WORK#"$REPO_ROOT"/}/compare/."
fi
