# Public release: distribution channels and policies

Research for distributing Tessera, an unofficial, open-source client (Android APK, macOS DMG) for
sankakucomplex.com, an adult image board. All sources are primary (policy pages, official docs, upstream repos),
checked **2026-11-01** unless stated otherwise. Quotes are verbatim; summaries are marked as such.

## Summary

| Channel | Viable? | Why |
| --- | --- | --- |
| Google Play | No | Bans pornography outright; also bans apps that use an API in a way that violates its terms. |
| F-Droid | No | Decided in 2021 to accept no new NSFW apps; that decision is still the open policy issue. |
| IzzyOnDroid | No | "Explicit content" is not welcome. |
| Samsung Galaxy Store | No | Bans apps that provide access to websites with a sexual emphasis. |
| Amazon Appstore (Android) | No | Shut down on 2025-08-20. |
| Accrescent | Unclear, unlikely to be worth it | Needs split APKs, a manual review and test login credentials; no adult content policy found. |
| GitHub Releases + Obtainium | Yes | Repo has no explicit assets; Obtainium tracks releases; `obtainium://` deep links add the app in one tap. |
| Homebrew Cask (official) | No unless notarized | Casks must pass Gatekeeper; failing casks are deprecated. A notability bar also applies. A self-hosted tap is possible. |
| DMG on GitHub Releases | Yes | Unsigned or ad-hoc signed apps open through System Settings → Privacy & Security → Open Anyway. |

## 1. Google Play

- **Sexual content.** Inappropriate Content → Sexual Content and Profanity: "We don't allow apps that contain or promote
  sexual content or profanity, including pornography". Nudity may be allowed only "if the primary purpose is
  educational, documentary, scientific or artistic" and it is not gratuitous. Source:
  <https://support.google.com/googleplay/android-developer/answer/9878810>
- **Third-party services and APIs.** Device and Network Abuse: "We don’t allow apps that interfere with, disrupt,
  damage, or access in an unauthorized manner the user’s device, other devices or computers, servers, networks,
  application programming interfaces (APIs), or services". One of the listed example violations: "Apps that access or
  use a service or API in a manner that violates its terms of service." Source:
  <https://support.google.com/googleplay/android-developer/answer/9888379>
- **Intellectual property.** "We don’t allow apps that infringe copyright" and "We don’t allow apps that induce or
  encourage copyright infringement". One example is "Streaming apps that allow users to download a local copy of
  copyrighted content without authorization." Source:
  <https://support.google.com/googleplay/android-developer/answer/9888072>
- **Conclusion (inference).** Not viable. The sexual content policy alone excludes it. Sankaku's terms prohibit
  "unauthorized User software" (see section 8 below), which would also make the app a Device and Network Abuse violation.

## 2. Android developer verification

Sources: <https://developer.android.com/developer-verification> and the FAQ,
<https://developer.android.com/developer-verification/guides/faq>.

- **Timeline (page as of 2026-11-01):**
  - August 2026: "Developer APIs, limited distribution accounts, and power user advanced flow launch."
  - 2026-09-30: "Protections begin for all users who install apps from participating stores in Brazil, Indonesia,
    Singapore, and Thailand on certified devices running Android 7+."
  - 2027: "Verification capability will be expanded globally for all apps on certified Android devices in 2027."
- **What this means for an APK on GitHub Releases now.** FAQ: "The September 30, 2026 deadline only applies to the
  specific participating stores. If you distribute your app through other stores, or if users sideload your app
  directly, these new verification requirements won't apply to your app yet. [...] we still recommend that you plan to
  complete your verification before the global rollout begins in 2027." Inference: from the 2027 global rollout,
  installing an APK from an unverified developer on a certified device will need either the advanced flow or `adb`.
- **Advanced flow for users.** FAQ: "a **one-time** setup for users to install apps from unverified developers after
  acknowledging the risks." Summary of the FAQ's steps: turn on developer mode, a check that the user is not being
  coached, a restart and re-authentication, then a one-day wait and biometric or PIN confirmation.
- **ADB.** FAQ: "As a developer, you are free to install apps without verification with ADB." Also: "there are no
  changes to how ADB works."
- **Limited distribution (hobbyist) account.** FAQ: "This lets you share apps with up to 20 specific devices for
  testing and personal use at no cost and without ID verification." A limited account can later be migrated to a full
  account, but not the other way round. Inference: 20 devices is too few for a public release.
- **Cost.** FAQ: "The $25 fee for the **Full Distribution** account in the ADC [...] We are waiving the fee for
  developers who qualify for a Limited Distribution account."
- **Package name and signing key.** These are registered. FAQ: "If you lose your signing key you won't be able to
  register your packages"; "The Android Developer Console lets you add and verify multiple signing keys for a single
  package"; "In cases where a package name is already in use, you may be informed that your key is not eligible to
  claim it outright. You should consider using a different package name."

## 3. F-Droid and IzzyOnDroid

### F-Droid

- **Inclusion policy.** Apps must be FLOSS and are built by F-Droid from source: "The F-Droid infrastructure compiles
  applications from publicly accessible source code". It forbids proprietary tracking and ad SDKs, and it weighs
  whether an app "is suitable for all F-Droid users, including children." Source:
  <https://f-droid.org/en/docs/Inclusion_Policy/>
- **NSFW apps.** fdroid/admin issue #252, "Plan on NSFW content", records the 2021-08-26 decision: "1. We will not
  allow any new NSFW apps (NSFW = apps that would require age verification in most countries). 2. We will add a new
  antifeature named NSFW [...] 4. We will keep the remaining NSFW apps in F-Droid so that users can keep receiving
  updates until we have a proper migration path." As of 2026-11-01 the issue is still open (last updated 2026-03-12).
  Its description now also carries an appended complaint from an issue editor that the decision "then [was] ignored
  for 4 years". Source: <https://gitlab.com/fdroid/admin/-/work_items/252>. Inference: a new NSFW client would be
  rejected.
- **Anti-features.** The valid anti-feature keys in fdroiddata are Ads, DisabledAlgorithm, KnownVuln, NoSourceSince,
  NonFreeAdd, NonFreeAssets, NonFreeDep, NonFreeNet, Tracking and TetheredNet. There is **no NSFW key**: the anti-feature
  promised in #252 is not in the list. Source: <https://gitlab.com/fdroid/fdroiddata/-/raw/master/config/antiFeatures.yml>.
  The docs page mentions NSFW only in its introduction. Source: <https://f-droid.org/en/docs/Anti-Features/>
  - **NonFreeNet:** "applied to apps that promote or depend entirely on a proprietary network service." A Sankaku
    client would get it.
  - **TetheredNet:** "apps that depend entirely on a service which is impossible (or not easy) to replace."
  - **Tracking:** examples include "Checking for updates without your knowledge or permission". The flag would not be
    applied "if the functionality is opt-in (i.e. you are asked before it happens) and disabled by default." This
    matters for an in-app GitHub update check.
- **Review time.** The quick start guide gives no review duration. After the fdroiddata merge request is merged, an app
  without build problems appears "somewhere around 24 to 48 hours" later. Source:
  <https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/>

### IzzyOnDroid

Source: the inclusion policy wiki, <https://gitlab.com/IzzyOnDroid/repo/-/wikis/Inclusion-Policy> (mirrored at
<https://codeberg.org/IzzyOnDroid/repodata/wiki/Inclusion-Policy>). Requests now go to Codeberg, per
<https://gitlab.com/IzzyOnDroid/repo/-/blob/master/README.md>.

- **Requirements.** The app must be FOSS with an OSI/FSF licence. APKs must be "signed by a release key" and must not
  be debuggable or testOnly. The APKs are taken from the project, preferably "attached to [Github] tagged `releases/`".
  The repo "usually reserves up to 30 megabytes per app", which is also the upper limit for a single APK.
- **Adult content.** "„Explicit content“ is not welcome here – mainly for the reason that all age groups incl.
  „minors“ should have access to this repo." Inference: not viable.

## 4. Obtainium

- **GitHub Releases tracking.** Obtainium uses the GitHub API for release info. Without a personal access token it can
  hit a "rate limit" error "if you have more than a few dozen GitHub apps". **Verify Latest Tag** picks the release
  marked "latest" instead of the newest one, at the cost of an extra API call. Source:
  <https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/HEAD/pages/sources.en.md>
- **Choosing the APK.** "When more than one APK is available for an app release, the user is prompted to pick one
  manually." A **Filter APKs by Regular Expression** option narrows the choice, and there is a filename-based CPU
  architecture filter. Same source. Inference: publish one universal APK per release so no filter is needed and
  background updates work.
- **Pre-releases and drafts.** In the code, `isReleaseExcluded` drops a release when `!includePrereleases &&
  release['prerelease'] == true`. `includePrereleases` is a per-app switch that defaults to `false`; drafts work the
  same way. Source: <https://github.com/ImranR98/Obtainium/blob/main/lib/app_sources/github.dart>
- **Version detection.** Obtainium compares the Android-reported version with the source's version string when both
  match a "standard" format such as `x.y.z`. It strips a leading `v` and suffixes like `-beta` from the source string,
  so the tag `v0.14.21-beta` matches the installed `0.14.21`. If the formats differ, version detection is turned off for
  that app. Silent background updates need Android 12+, a recent target SDK, a previous install by Obtainium, and a
  single matching APK. Source: <https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/HEAD/pages/app_tracking.en.md>.
  Inference: tag releases `vX.Y.Z` with `versionName` `X.Y.Z`.
- **One-tap adding and badges.** Obtainium registers `obtainium://`:
  - `obtainium://add/<url>` pre-fills the Add screen.
  - `obtainium://app/<url-encoded JSON>` imports a full config after a confirmation dialog.
  - "a project can put a 'Get it on Obtainium' badge on its own README [...] no listing in the crowdsourced directory
    required". Copy `assets/graphics/badge_obtainium.png` from the main repo and host it yourself.
  - For visitors who don't have Obtainium yet, the docs recommend the redirect
    `https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/<encoded json>`.

  Source: <https://github.com/ImranR98/wiki.obtainium.imranr.dev/blob/HEAD/pages/deep_links.en.md>. There is also a
  crowdsourced config directory at <https://apps.obtainium.imranr.dev>, per
  <https://github.com/ImranR98/Obtainium/blob/main/README.md>.
- **Signing-key consistency (Android itself).** "When the system is installing an update to an app, it compares the
  certificate(s) in the new version with those in the existing version. The system allows the update if the
  certificates match. If you sign the new version with a different certificate, you must assign a different package
  name to the app". Also: "If you lose or misplace your key, you will not be able to publish updates to your existing
  app." Source: <https://developer.android.com/studio/publish/app-signing>. Obtainium's own README publishes its
  package name and the SHA-256 of its signing certificate for verification. That is a pattern worth copying.

## 5. Other Android channels

- **Amazon Appstore:** "August 20, 2025 | Amazon Appstore for Android is now discontinued. You can no longer submit or
  update apps for Android mobile devices." Source: <https://developer.amazon.com/docs/app-submission/release-notes.html>
- **Samsung Galaxy Store:** Distribution guide 2.1.1, "Apps must not visually or audibly present or encourage overt
  sexual concepts or content". 2.1.3, "Apps must not provide a method to access websites that have a sexual emphasis."
  Source: <https://developer.samsung.com/galaxy-store/distribution-guide.html>. Not viable.
- **Accrescent:** Apps are submitted through its developer console (GitHub login) as signed split APKs (`.apks`) and go
  through manual review. Apps behind a login need test credentials sent to the team. No content policy on adult apps
  was found. The FAQ notes apps need not be open source. Sources: <https://accrescent.app/faq/>,
  <https://accrescent.app/docs/guide/appendix/requirements.html>. Not verified: whether new developers can sign up
  today.

## 6. macOS

- **Sequoia removed Control-click.** "In macOS Sequoia, users will no longer be able to Control-click to override
  Gatekeeper when opening software that isn't signed correctly or notarized. They'll need to visit System Settings >
  Privacy & Security to review security information for software before allowing it to run." Apple Developer News,
  2024-08-06: <https://developer.apple.com/news/?id=saqachfa>
- **Open Anyway path.** Apple Support, "Safely open apps on your Mac" (published 2026-05-27). "After you've tried to
  open the app [...] Open System Settings. Click Privacy & Security, scroll down, and click the Open Anyway button to
  confirm your intent to open or install the app. The warning prompt reappears and [...] you can click Open. The app
  is now saved as an exception to your security settings". Source: <https://support.apple.com/en-us/102445>
- **Ad-hoc signing.** On Apple silicon, "any executable must be signed before it’s allowed to run. There isn’t a
  specific identity requirement for this signature: a simple ad-hoc signature is sufficient." But "given that these
  signatures do not bear any valid identity, binaries signed this way cannot pass through Gatekeeper." Source:
  <https://developer.apple.com/documentation/macos-release-notes/macos-big-sur-11_0_1-universal-apps-release-notes>.
  Inference: an ad-hoc signed DMG app launches only through Open Anyway.
- **Apple Developer Program.** "$99 annual membership", with no fee waiver stated
  (<https://developer.apple.com/programs/>). Only the program's Account Holder can generate a Developer ID certificate,
  and notarization gives Gatekeeper a ticket (<https://developer.apple.com/developer-id/>).
- **Homebrew Cask (official tap).**
  - Executables "must pass Homebrew's Gatekeeper checks and must not require System Integrity Protection or Gatekeeper
    to be disabled or bypassed." Source: <https://docs.brew.sh/Acceptable-Casks>
  - "Homebrew therefore deprecates, disables and eventually removes casks that fail Gatekeeper checks rather than
    normalising a security bypass." Source: <https://docs.brew.sh/Homebrew-Security-and-Supply-Chain> (doc's
    last_review_date 2026-10-25).
  - Failing Gatekeeper checks is a deprecation reason (`:fails_gatekeeper_check`). Source:
    <https://docs.brew.sh/Deprecating-Disabling-and-Removing>
  - Notability: a self-submission by the repository owner needs "at least 90 forks, 90 watchers or 225 stars". Source:
    <https://docs.brew.sh/Package-Acceptance-Policy>
  - Inference: an unnotarized Tessera cask is not accepted into `homebrew/cask`. A self-hosted tap is outside these
    rules, but users still go through Gatekeeper (Homebrew applies quarantine attributes to cask downloads).

## 7. GitHub

- **Sexually obscene content (Acceptable Use Policies).** "We do not allow sexually themed or suggestive content that
  serves little or no purpose other than to solicit an erotic or shocking response [...] This includes: Pornographic
  content [...] Graphic depictions of sexual acts including photographs, video, animation, drawings, computer-generated
  images, or text-based content". The policy does not mention software that accesses such content. Source:
  <https://docs.github.com/en/site-policy/acceptable-use-policies/github-sexually-obscene-content>.
  Inference: a client with no explicit assets in the repo, README, screenshots or release notes is outside the policy's
  text. The risk is in screenshots and evidence images, which should be SFW (rating G) only.
- **Latest release endpoint.** `GET /repos/{owner}/{repo}/releases/latest`: "The latest release is the most recent
  non-prerelease, non-draft release, sorted by the `created_at` attribute." Source:
  <https://docs.github.com/en/rest/releases/releases#get-the-latest-release>. Checked by request: it returns `200` without
  a token and sends an `etag`.
- **Unauthenticated rate limit.** "The primary rate limit for unauthenticated requests is 60 requests per hour."
  Unauthenticated requests "are associated with the originating IP address". Source:
  <https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api>
- **Conditional requests don't help without a token.** A `304` does not count "if [...] the request was made while
  correctly authorized with an `Authorization` header". Source:
  <https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests>.
  Inference: unauthenticated `304`s still count against the limit.
- **Stable download link.** `/releases/latest/download/<asset-name>` links straight to the latest release's uploaded
  asset. Source: <https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases>

## 8. Sankaku's terms of service and sign-up page

Verified in a real browser (T3 Code's preview, no bot check met) on **2026-11-03** (#211). Without signing in or
creating an account.

- **Terms URL:** <https://legal.sankakucomplex.com/terms-of-service>, "Last Updated: July 11th, 2026". The login
  pages' "Terms" footer link goes there (with a `theme` parameter). The page itself cites `legal.sankaku.app` for the
  incorporated Privacy Policy and Creator Terms.
- **The clauses.** The terms' items are not numbered. Both are in the "Prohibited Conduct" list under **Rules of
  Conduct**, which opens "You further agree that you will not engage in any of the following Prohibited Conduct:".
  Verbatim:
  - "Any act which imposes excessive burden on the servers or administration of the Platform, or which could
    potentially cause such burden directly or indirectly."
  - "Use of the Platform or its constituent API in conjunction with unauthorized User software, or unauthorized access
    to the Platform generally."

  "Unauthorized User software" is not defined anywhere in the terms.
- **Remedies** (same section): "We reserve the right, in our sole discretion, to protect Users from violators and
  violations of these Rules of Conduct, including but not limited to restricting your use of the Platform, immediately
  terminating your use of the Platform, or terminating your use of the Platform by blocking certain IP addresses from
  accessing the Platform."
- **Minimum age** (Eligibility): "You must be at least eighteen years old to use the Platform as a User."
- **Sign-up URL:** <https://login.sankakucomplex.com/registration>. It redirects to a "Create account | Sankaku
  Complex" form (username, password, confirm password, email; "By creating an account, you agree to the Terms of
  Service"), with a per-visit session in its query, so the bare path is the one to link. It's the page the "CREATE
  FREE ACCOUNT" button on <https://login.sankakucomplex.com/> opens. The web app's own SSO host is
  `login.sankakucomplex.com` (`SSO_API_URL` in its bundle).
- **The earlier attempt** (2026-11-01) fetched without a browser: every path on www.sankakucomplex.com returned the
  app shell, and the bundle host returned 403. The terms are on the `legal.` host, which that attempt didn't try.

The repo's earlier quotes of the two clauses (`docs/designs/sankaku-kmp-client.md`, Security and privacy;
`README.md`) are verbatim substrings of them; the README's and the sign-in notice's "account" in the remedies is a
paraphrase of "use of the Platform". The app links the sign-up page from its sign-in screen (#211); it registers nothing itself.
