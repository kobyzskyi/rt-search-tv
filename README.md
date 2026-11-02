# RT Search TV

An Android TV app for searching the RuTracker torrent tracker with a remote control: search with sorting
and category filters, release details with poster and description, and hand-off of magnet links or
`.torrent` files to an installed torrent client (TorrServe, LibreTorrent and others).

## Cloudflare

The tracker sits behind Cloudflare, which rejects plain HTTP clients with `403 cf-mitigated: challenge`.
The app does not try to get around the check; the user passes it in a real browser engine:

* All requests go through one WebView: `fetch()` runs inside a tracker page, so requests carry the same
  cookies (`cf_clearance`, `bb_session`), User-Agent and TLS fingerprint as the browser that passed the check.
* When a page is challenged, the app opens it in the WebView and waits about 12 seconds, since the check
  usually passes by itself. If it does not, the page is shown full screen so the user can complete it with
  the remote; it closes automatically afterwards.
* Cookies are persisted, so the check and the sign-in are rarely needed.

## Features

* Search sorted by seeds, date, size or downloads, with "show more" paging and recent searches.
* Category filter: a whole tracker category or a single forum with its subforums. A default category can be
  set in Settings and is applied every time the app starts.
* Release page: poster, size, seeds and leeches, scrollable description, "Open magnet" and "Download .torrent".
* Sign-in with username and password, or on the website when the tracker asks for a captcha.
* Mirror selection: rutracker.org, rutracker.net, rutracker.nl.
* Automatic updates from GitHub releases (see below).

## Updates

On every start (can be turned off in Settings) the app asks the GitHub API for the latest
release of this repository. If its version is newer than the installed one, a banner offers to install it: the
APK is downloaded and handed to the system installer. Android asks once to allow installing unknown apps
from RT Search.

## Building

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it over the network
(enable network/ADB debugging on the TV first):

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Pushing a tag `vX.Y.Z` that matches `versionName` in `app/build.gradle.kts` runs the
[release workflow](.github/workflows/release.yml), which builds a signed APK and publishes a GitHub release.
Remember to bump `versionCode` as well.

The workflow needs these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The release keystore, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Local release builds read the same values from a `keystore.properties` file in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), which is ignored by git. Every release must be
signed with the same key, otherwise Android refuses to install it over the previous version.

## Project layout

* `web/WebBridge.kt`: requests through the WebView, Cloudflare challenge detection and display.
* `data/Rutracker.kt`: search (`tracker.php`), releases (`viewtopic.php`), sign-in, `.torrent` downloads
  (`dl.php`), HTML parsing with Jsoup (windows-1251).
* `update/Updater.kt`: GitHub release check, APK download and installation.
* `AppController.kt`: screen state, search history, settings, launching the torrent client.
* `ui/`: search, release, categories and settings screens built for remote control navigation.
