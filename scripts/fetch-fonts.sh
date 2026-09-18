#!/usr/bin/env bash
# Fetch the two typefaces the design calls for into app/src/main/res/font/.
#
#   ./scripts/fetch-fonts.sh
#
# Caprasimo (headings) and Figtree (body) are both SIL OFL 1.1, so they can be redistributed inside
# the APK and are fine for F-Droid. They are bundled rather than pulled at runtime on purpose: the
# downloadable-fonts provider needs Google Play Services, which F-Droid will not take, and it would
# put a network round trip on the cold-start path.
#
# The TTFs are committed once fetched — the build must not need the network.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

font_dir="$REPO_ROOT/app/src/main/res/font"
base="https://raw.githubusercontent.com/google/fonts/main/ofl"

mkdir -p "$font_dir"

# Android resource names must be lowercase, letters/digits/underscore only.
fetch() {
  local url="$1" dest="$2"
  info "Fetching $(basename "$dest")"
  curl -fsSL --retry 3 --max-time 60 -o "$dest" "$url" || die "Could not fetch $url"
  # A 404 page would land as a small HTML file; a real TTF starts with 0x00010000 or "OTTO"/"true".
  [ -s "$dest" ] || die "$dest is empty"
  local size
  size=$(stat -c %s "$dest")
  [ "$size" -gt 10000 ] || die "$dest is only ${size} bytes — that is not a font"
}

fetch "$base/caprasimo/Caprasimo-Regular.ttf" "$font_dir/caprasimo_regular.ttf"

# Figtree ships as a variable font upstream; the static instances carry the weights the design uses
# (400 body, 600 for item names, 700 for the sync pill and tab labels).
fetch "$base/figtree/static/Figtree-Regular.ttf" "$font_dir/figtree_regular.ttf"
fetch "$base/figtree/static/Figtree-SemiBold.ttf" "$font_dir/figtree_semibold.ttf"
fetch "$base/figtree/static/Figtree-Bold.ttf" "$font_dir/figtree_bold.ttf"

# Ship the licence beside the fonts: OFL requires the notice travel with the files.
fetch "$base/caprasimo/OFL.txt" "$font_dir/../../../../LICENSES/OFL-Caprasimo.txt"
fetch "$base/figtree/OFL.txt" "$font_dir/../../../../LICENSES/OFL-Figtree.txt"

info "Fonts in $font_dir"
ls -la "$font_dir"

cat <<'EOF'

Next step: point the theme at them. In
app/src/main/java/io/github/molleware/porygonlist/theme/Type.kt
replace the two fallback families with:

  val HeadingFont = FontFamily(Font(R.font.caprasimo_regular, FontWeight.Normal))
  val BodyFont = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
  )

adding imports for androidx.compose.ui.text.font.Font and
io.github.molleware.porygonlist.R.
EOF
