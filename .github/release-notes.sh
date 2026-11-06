#!/usr/bin/env bash
# Prints the body of the GitHub release of one version: its CHANGELOG.md section, then how to open the DMG and check
# the files. The release workflow (.github/workflows/release.yml) runs it before anything is built.
#   .github/release-notes.sh <version> <repository> [changelog]
#     <version>      X.Y.Z, without the v
#     <repository>   owner/name, for the gh attestation verify commands
#     [changelog]    default CHANGELOG.md
# The section starts at a "## <version>" or "## [<version>]" heading (anything may follow it, such as " - 2026-11-10")
# and ends at the next "## " heading or link reference ("[100.0.0]: https://…"). It fails when the file or the section
# is missing, or the section is empty. GitHub shows a single newline in a release body as a line break, so each
# paragraph below is one line.
set -euo pipefail

die() { echo "release-notes: $*" >&2; exit 1; }
[ $# -ge 2 ] && [ $# -le 3 ] || die "usage: release-notes.sh <version> <repository> [changelog]"
version=$1 repository=$2 changelog=${3:-CHANGELOG.md}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "the version must be X.Y.Z, not $version"
[ -f "$changelog" ] || die "no $changelog: add the $version section before tagging"

# String comparisons only, no regex built from the version: the workflow runs this with Ubuntu's mawk, the tests with
# macOS's awk.
section=$(awk -v version="$version" '
  function is_heading(line, rest, n) {
    if (substr(line, 1, 3) != "## ") return 0
    rest = substr(line, 4)
    n = length(version)
    if (substr(rest, 1, 1) == "[") {
      if (substr(rest, 2, n + 1) != version "]") return 0
      rest = substr(rest, n + 3)
    } else {
      if (substr(rest, 1, n) != version) return 0
      rest = substr(rest, n + 1)
    }
    return rest == "" || substr(rest, 1, 1) == " " || substr(rest, 1, 1) == "\t"
  }
  found && (substr($0, 1, 3) == "## " || (substr($0, 1, 1) == "[" && index($0, "]: ") > 2)) { exit }
  found { print; next }
  is_heading($0) { found = 1 }
  END { if (!found) exit 3 }
' "$changelog") || {
  [ $? -eq 3 ] && die "$changelog has no \"## $version\" section: add it before tagging"
  die "couldn't read $changelog"
}
# Without the blank lines around it.
section=$(printf '%s\n' "$section" | awk 'NF { started = 1 } started' | awk '
  { lines[NR] = $0 } NF { last = NR }
  END { for (i = 1; i <= last; i++) print lines[i] }
')
[ -n "$section" ] || die "the $version section of $changelog is empty"

cat <<EOF
$section

---

**macOS:** the DMG isn't notarized, so macOS refuses to open the app the first time. Try once, then allow it under System Settings → Privacy & Security → **Open Anyway**.

**Checking a download:** each APK and DMG has a build provenance attestation, which shows it was built from this repository's \`v$version\` tag by its release workflow. With the [GitHub CLI](https://cli.github.com):

\`\`\`sh
gh attestation verify Tessera-$version.apk --repo $repository
gh attestation verify Tessera-$version.dmg --repo $repository
\`\`\`
EOF
