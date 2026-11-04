package dev.marzahn.sankaku

/**
 * The public mirror on GitHub (#201), `owner/name`: its releases are what users install, its issue forms take bug
 * reports, and About links its source. The update check (#209), Report a bug (#207) and About (#206) all build their
 * URLs from it, so a rename is one change here.
 */
const val PUBLIC_REPO = "dev-marzahn/tessera-app"

/** The mirror's page on GitHub. */
const val PUBLIC_REPO_URL = "https://github.com/$PUBLIC_REPO"
