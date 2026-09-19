#!/usr/bin/env bash
# Render the launcher icon the way a phone will show it.
#
#   ./scripts/icon.sh            write build/icon-preview.png
#   ./scripts/icon.sh open       write it and open it in the image viewer
#
# The icon lives in three vector drawables that no editor previews usefully, and
# the thing that actually matters — whether the bird still reads once a launcher
# has cropped it to a circle and scaled it to 48px — is invisible until it is
# rendered. This produces one sheet: the full 108dp canvas, the circle and
# squircle masks, the two small sizes, and the themed silhouette.
#
# Nothing here is part of the build. It needs Inkscape and ImageMagick, which
# are only useful if you are editing the artwork.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly RES="$REPO_ROOT/app/src/main/res/drawable"
readonly OUT="$REPO_ROOT/build/icon-preview.png"

command -v inkscape >/dev/null || die "Inkscape is not installed; it renders the vector drawables."
command -v magick   >/dev/null || die "ImageMagick is not installed; it applies the launcher masks."

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Vector drawables are SVG wearing an Android costume: the path data is the same
# grammar, only the attribute names differ. Flattening the file to one line first
# means a <path> split across lines still comes out as a single element.
#
# The width and height are written out as well as the viewBox so that the page
# is the 108dp canvas no matter what renders the file. That matters for the
# monochrome layer, which does not reach the edges: anything that exports the
# drawing's bounding box instead of the page scales it up, and the preview then
# shows a bird that is not the size the phone will draw.
to_svg() {
  local out="$1"; shift
  printf '<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">\n' >"$out"
  local file element fill data
  for file in "$@"; do
    while read -r element; do
      fill="$(grep -oP 'fillColor="\K[^"]+' <<<"$element" || true)"
      data="$(grep -oP 'pathData="\K[^"]+' <<<"$element" || true)"
      [[ -n "$data" ]] || continue
      printf '  <path fill="%s" d="%s"' "${fill:-#000000}" "$data" >>"$out"
      grep -q 'fillType="evenOdd"' <<<"$element" && printf ' fill-rule="evenodd"' >>"$out"
      printf '/>\n' >>"$out"
    done < <(tr '\n' ' ' <"$file" | grep -oP '<path\b[^>]*>')
  done
  printf '</svg>\n' >>"$out"
}

to_svg "$work/icon.svg" "$RES/ic_launcher_background.xml" "$RES/ic_launcher_foreground.xml"
to_svg "$work/mono.svg" "$RES/ic_launcher_monochrome.xml"

inkscape "$work/icon.svg" -o "$work/full.png" -w 432 -h 432 2>/dev/null
inkscape "$work/mono.svg" -o "$work/mono.png" -w 432 -h 432 2>/dev/null

# A launcher shows the middle 72dp of the 108dp canvas, masked to its own shape.
magick "$work/full.png" -gravity center -crop 288x288+0+0 +repage "$work/visible.png"
magick -size 288x288 xc:none -fill white -draw "circle 144,144 144,2" "$work/circle-mask.png"
magick -size 288x288 xc:none -fill white -draw "roundrectangle 2,2 286,286 78,78" "$work/squircle-mask.png"
for mask in circle squircle; do
  magick "$work/visible.png" "$work/$mask-mask.png" \
    -alpha Off -compose CopyOpacity -composite "$work/$mask.png"
done

# The sizes worth judging: a launcher grid is around 96px, a notification or a
# recents card around 48px. If it fails here, it fails on the phone.
for size in 96 48; do
  magick "$work/circle.png" -resize "${size}x${size}" \
    -background '#C8C8C8' -gravity center -extent 288x288 "$work/small-$size.png"
done

# Themed icons keep only this layer's alpha and tint it, so preview it tinted.
magick "$work/mono.png" -gravity center -crop 288x288+0+0 +repage \
  -fill '#B7D3C2' -colorize 100 -background '#26332C' -alpha remove "$work/themed.png"

mkdir -p "$(dirname "$OUT")"
magick "$work/full.png" -resize 288x288 "$work/square.png"
magick "$work/square.png" "$work/circle.png" "$work/squircle.png" \
  "$work/small-96.png" "$work/small-48.png" "$work/themed.png" \
  -background '#C8C8C8' -alpha remove -alpha off +append "$OUT"

info "Wrote $OUT"

if [[ "${1:-}" == "open" ]]; then
  command -v xdg-open >/dev/null || die "No xdg-open; open $OUT yourself."
  xdg-open "$OUT" >/dev/null 2>&1 &
fi
