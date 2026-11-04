# Tessera

Tessera is an unofficial client for [sankakucomplex.com](https://www.sankakucomplex.com), for Android and macOS. You
can browse and search posts, open them in tabs, and favourite, vote and react with your own Sankaku account.

> **Unofficial, and at your own risk.** Tessera isn't made by Sankaku or affiliated with it. Sankaku's
> [terms of service](https://legal.sankakucomplex.com/terms-of-service) prohibit "Use of the Platform or its constituent
> API in conjunction with unauthorized User software" (the terms don't define it) and "Any act which imposes excessive
> burden on the servers or administration of the Platform". Sankaku reserves the right to restrict or terminate your
> use of it, or to block your IP address. Tessera limits its own request rate, but the risk is yours.
>
> **18+ only.** Sankaku is an adult site, and its terms require you to be at least 18. Tessera asks you to confirm
> this on its first launch.

## Install

Every release is on the [Releases page](../../releases/latest): `Tessera-<version>.apk` for Android and
`Tessera-<version>.dmg` for macOS.

### Android

Android 12 or newer.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/dev-marzahn/tessera-app"><img src="docs/obtainium-badge.png" alt="Get it on Obtainium" height="48"></a>

- **With [Obtainium](https://obtainium.imranr.dev)** (recommended), which installs Tessera and notifies you of each
  new release. On your phone, tap the badge above, or open `obtainium://add/https://github.com/dev-marzahn/tessera-app`.
  Or, in Obtainium, tap **Add app** and paste `https://github.com/dev-marzahn/tessera-app`.
- **By hand:** download `Tessera-<version>.apk` from the Releases page on your phone and open it. Android asks you
  once to let your browser or file manager install apps. Install each update the same way; it keeps your data.

### macOS

A Mac with Apple silicon.

1. Download `Tessera-<version>.dmg`, open it and drag Tessera to Applications.
2. Open Tessera. The DMG **isn't notarized** by Apple, so the first time macOS refuses to open it and says it can't
   verify it. Click **Done**.
3. Open System Settings → **Privacy & Security**, scroll to **Security**, and click **Open Anyway** next to the
   message about Tessera. The button is there for about an hour after you try to open the app.
4. Enter your login password and click **OK**, then **Open Anyway** once more if macOS asks.

macOS then opens Tessera as usual. When there's a newer release, Tessera shows "Update available" in Settings →
Updates. Download its DMG, replace the app in Applications, and allow the new version once with steps 2 to 4.

## Check a download

Each APK and DMG on the Releases page has a build provenance attestation: it shows that GitHub's release workflow built
the file from this repository's tag. With the [GitHub CLI](https://cli.github.com):

```sh
gh attestation verify Tessera-<version>.apk --repo dev-marzahn/tessera-app
gh attestation verify Tessera-<version>.dmg --repo dev-marzahn/tessera-app
```

## Privacy

Tessera contacts only two services:

- **Sankaku:** its API, sign-in and image servers, for everything you browse. Signing in is optional.
- **GitHub:** once a day at most, at launch, it asks GitHub's API for this repository's newest release, without any
  account or token. Settings → Updates → **Check for updates** turns this off.

There are no analytics, no telemetry and no ads, and no crash or bug report is ever sent automatically. Your password is sent only to
Sankaku when you sign in, and stored nowhere. Settings → Diagnostics shows a log of the app's requests (paths, statuses
and timings, never search terms, ids or tokens). It's kept in memory only. **Copy log** and **Report a bug** let you
paste it into a bug report yourself.

Everything Tessera stores stays on your device:

| What | macOS | Android |
| --- | --- | --- |
| Sign-in tokens, with your account's id, name and avatar address | The login keychain, as `dev.marzahn.tessera` | App storage (`files/datastore/tokens.preferences_pb`), encrypted with AES-256-GCM; that key (`shared_prefs/tessera_token_keyset.xml`) is itself encrypted with a key in the Android Keystore |
| Saved tabs: their searches, places and recently closed tabs (`tabs.json`, and its copies `tabs.v1.bak` and `tabs.json.broken`) | App data | App storage |
| Tags learnt from Sankaku, for search chips and autocomplete (`tags.json`) | App data | App storage |
| Posts you've seen: id, md5 and when, at most 50,000 and for 90 days (`seen.json`) | App data | App storage |
| That you accepted the first-launch screen (`first-launch.json`) | App data | App storage |
| Settings: Hide in Recents, G only and how it starts (`privacy.json`), the update check and the newest release it found (`updates.json`), the queue (`queue.json`), video volume (`player.json`) | App data | App storage |
| Android-only settings: queue buttons (`accessibility.json`), tips already shown (`tips.json`) | — | App storage |
| The last crash that closed the app: when, the version, and the exceptions' class names and stack frames, never their messages (`crash.txt`). It stays until the next launch shows it and you report or dismiss it; a crash after that waits for the launch after | App data | App storage |
| Image cache: previews, samples and full images, up to 1 GB | `~/Library/Caches/Tessera/images/` | App cache (`cache/images/`) |
| Video cache, up to 128 MB | — (videos stream without one) | App cache (`cache/video/`), with its index in `databases/exoplayer_internal.db` |
| Posts you save | `~/Downloads/Tessera/` | `Download/Tessera/`, visible to other apps |

- **App data** on macOS is `~/Library/Application Support/Tessera/`, which also holds `instance.lock`, so that only
  one Tessera runs at a time.
- **App storage** on Android is the app's private storage. Tessera opts out of Android's cloud backup, so none of it
  goes to Google. A device-to-device transfer to a new phone can still copy it; the tokens can't be decrypted there,
  so you sign in again. Uninstalling Tessera deletes it all, except the posts you saved.
- **Clearing it:** Settings → Account → **Sign out** deletes the tokens. Settings → Storage → **Clear cache** empties
  both caches, and **Clear saved tabs** (on a phone, **Clear the queue**) the tabs. Settings → Privacy → **Clear
  everything** does both, and also forgets the posts you've seen, your recent searches and the tags learnt.

## Report a bug

Open an [issue](../../issues/new/choose) with the bug or feature form. In the app, Settings → Diagnostics → **Report a
bug** opens the bug form with your version, OS and device filled in. If Tessera closes because of a crash, the next
launch shows "Tessera closed unexpectedly" with the report. **Report** copies it and opens the bug form for you to
paste it into; **Dismiss** doesn't. Either one deletes the saved report. On macOS, an error that leaves the app running isn't saved.

This repository takes **issues only, not pull requests**: each release replaces its history with one commit, so a pull
request can't be merged. If you have a fix, describe it or paste the patch in an issue
([`CONTRIBUTING.md`](CONTRIBUTING.md)).

## Build from source

You need a JDK (Gradle downloads JDK 25 for itself if needed) and the Android SDK, with its path in `local.properties`
(`sdk.dir=…`).

```sh
./gradlew :desktopApp:run              # run the desktop app
./gradlew :androidApp:assembleDebug    # a debug APK, in androidApp/build/outputs/apk/debug/
./gradlew build                        # every target and all tests
```

A debug APK is signed with another key than the releases, so Android installs it only after you uninstall a release,
which deletes the app's data. How the app works is in [`docs/designs/sankaku-kmp-client.md`](docs/designs/sankaku-kmp-client.md),
and what's known about Sankaku's API in [`docs/api.md`](docs/api.md).

## Licence

Copyright the Tessera authors. Tessera is free software under the GNU General Public License, version 3 only
(GPL-3.0-only); see [`LICENSE`](LICENSE). Settings → About lists the open-source libraries it uses and their licences.

The "Get it on Obtainium" badge is from [Obtainium](https://github.com/ImranR98/Obtainium) (GPL-3.0).
