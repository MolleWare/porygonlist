#!/usr/bin/env bash
# Build the signed APK for a tagged release, ready to attach to the GitHub release.
#
#   ./scripts/release.sh          on the host, with HEAD tagged v<versionName>
#
# F-Droid publishes our APK only if its own build of the tagged commit matches it byte for byte,
# signature aside, and the APK records the commit it was built from
# (META-INF/version-control-info.textproto). So this refuses to run unless the working tree is
# clean and HEAD carries the tag for the version in app/build.gradle.kts, then:
#
#   1. builds the signed release from that commit (asks for the key's password);
#   2. checks the APK's version and that its certificate is the one in the F-Droid draft;
#   3. copies it to build/release/porygonlist-<version>.apk, the name the draft's Binaries expects.
#
# It publishes nothing. It prints the push, the GitHub release and the F-Droid check to run next.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly DRAFT="$REPO_ROOT/docs/fdroid/io.github.molleware.porygonlist.yml"
readonly APK="$REPO_ROOT/app/build/outputs/apk/release/app-release.apk"

[[ -z "${PORYGONLIST_DEV_CONTAINER:-}" ]] || die "Release signing happens on the host."

version="$(grep -oE 'versionName = "[^"]+"' "$REPO_ROOT/app/build.gradle.kts" | cut -d'"' -f2)"
code="$(grep -oE 'versionCode = [0-9]+' "$REPO_ROOT/app/build.gradle.kts" | grep -oE '[0-9]+')"
tag="v$version"

[[ -z "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]] \
  || die "The working tree has changes. The APK must be built from the tagged commit alone."
[[ "$(git -C "$REPO_ROOT" rev-parse HEAD)" == "$(git -C "$REPO_ROOT" rev-parse -q --verify "$tag^{commit}" 2>/dev/null)" ]] \
  || die "HEAD is not tagged $tag. Tag it first: git tag -a $tag -m \"PorygonList $version\""
grep -q "versionName: $version" "$DRAFT" && grep -q "versionCode: $code" "$DRAFT" && grep -q "commit: $tag" "$DRAFT" \
  || die "The F-Droid draft has no build entry for $version ($code) at $tag."

info "Building the signed release of $tag"
gradlew_signed clean assembleRelease

require_android_sdk
build_tools="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"

info "Checking the APK"
badging="$("$build_tools/aapt2" dump badging "$APK")"
grep -q "versionCode='$code' versionName='$version'" <<<"$badging" \
  || die "The APK is not $version ($code): $(head -1 <<<"$badging")"

expected="$(grep -E '^AllowedAPKSigningKeys:' "$DRAFT" | awk '{print $2}')"
actual="$("$build_tools/apksigner" verify --print-certs "$APK" | grep -m1 'certificate SHA-256 digest' | awk '{print $NF}')"
[[ -n "$actual" && "$actual" == "$expected" ]] || die "Signed with $actual, but the F-Droid draft expects $expected."

recorded="$(unzip -p "$APK" META-INF/version-control-info.textproto | grep -oE 'revision: "[0-9a-f]+"' | cut -d'"' -f2)"
[[ "$recorded" == "$(git -C "$REPO_ROOT" rev-parse HEAD)" ]] || die "The APK records commit '$recorded', not HEAD."

out="$REPO_ROOT/build/release/porygonlist-$version.apk"
mkdir -p "$(dirname "$out")"
cp "$APK" "$out"
info "Ready: $out"
echo "  version $version ($code), certificate $actual, built from $recorded"

cat <<EOF

Next, in this order:
  git push origin main $tag
  ./scripts/fdroid-check.sh $tag
  gh release create $tag "$out" --title "PorygonList $version" --notes-file fastlane/metadata/android/en-US/changelogs/$code.txt
EOF
