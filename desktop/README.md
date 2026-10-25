# Meld for Windows

A desktop version of Meld built with Compose Multiplatform. It reuses the Android app's
platform-independent code (YouTube Music / InnerTube, Spotify, lyrics clients) straight from
`../innertube` and `../app/src/main/kotlin/com/metrolist/*`, and replaces the Android-only parts:

| Android app                         | Desktop app                                              |
| ----------------------------------- | -------------------------------------------------------- |
| Jetpack Compose screens             | Compose for Desktop screens (`ui/`)                      |
| ExoPlayer / Media3                  | libVLC via vlcj (`playback/VlcAudioEngine.kt`)           |
| OkHttp data source with headers     | Local stream proxy (`playback/StreamProxy.kt`)           |
| `InnerTubeXPlayer` + WebView tokens | `playback/StreamResolver.kt` (no PO token provider)      |
| DataStore                           | `%APPDATA%\Meld\settings.json`                           |

Playback is ad-free: audio streams are fetched directly, so YouTube's ads never load.
SponsorBlock skipping is on by default for non-music parts, sponsors and self-promotion.

## Spotify

Settings → **Sign in with Spotify** opens Spotify's login page in a separate Microsoft Edge
(or Chrome) window with its own profile. Once signed in, Meld reads the `sp_dc` session cookie
through the browser's DevTools protocol and closes the window (`spotify/SpotifyLogin.kt`); this
replaces the Android app's WebView login. The cookie is turned into web-player tokens the same way
as on Android. With a Spotify account connected:

- Home shows your top tracks, favorite artists and Spotify's personalized home sections.
- Song search uses Spotify, and Library lists your liked songs and playlists.
- Spotify tracks are matched to YouTube Music songs only when they're about to play, with the
  Android app's fuzzy matching; matches are cached in `%APPDATA%\Meld\spotify-matches.json`.
- Radio from a Spotify track uses a port of the Android recommendation engine.

## Getting the app

Every push that touches the desktop code runs the **Desktop (Windows)** GitHub Actions workflow.
Open the run and download the `Meld-windows` artifact. It contains:

- `Meld-1.0.0.msi`: installer (per-user, no admin rights needed)
- `Meld-portable-windows.zip`: unzip and run `Meld.exe`

Both include libVLC, so VLC doesn't need to be installed. Pushing a tag named `desktop-v*`
also publishes them as a GitHub release.

## Building locally

Requires JDK 21.

```
cd desktop
./gradlew run              # start the app (uses an installed 64-bit VLC)
./gradlew test             # unit tests
./gradlew packageMsi       # Windows installer, run on Windows
```

## Not ported yet

Manual match override, Spotify playlist editing, YouTube Music account login and library sync,
lyrics display, downloads, Listen Together, Discord presence, equalizer and media-key support.
