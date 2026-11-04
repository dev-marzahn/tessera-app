# The app is called Tessera

An unofficial client can't ship under Sankaku's name or logo, so before the first release the app became Tessera, with
its own icon (a grid of tiles, one an open square). Everything a user sees changes, along with the identifiers that
are hard to change after the first install: `applicationId` and the macOS bundle ID `dev.marzahn.tessera`, the keychain
service and the app data and cache folders. Desktop moves its old app data on first launch, and its user signs in once
more. The Kotlin packages, module names and repository keep `sankaku`: they're invisible to users, and renaming them
touches every file. The README still says what the app is for: an unofficial client for sankakucomplex.com.

**Consequences:** the official logo is removed from the repository, and no future asset or string may reuse it. A
Compose Multiplatform image-viewer library is also called Tessera; it isn't used here.
