# RootBeat

RootBeat is a GPL-3.0-or-later Android music player. It is a fork of [Pixel Music](https://github.com/ianshulyadav/PixelMusicApp), which is itself built on [PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS).

The RootBeat build keeps the upstream player and adds:

- A **RootBeat** name, colors, and launcher icon
- An **audio visualizer** on the full player
- **RootBeat Jam**, a listen-together party on the same Wi-Fi

People on the same network join with a code and the host phone's IP address. Playback stays in sync, and guests can add songs from the host library. The host phone shares the current library track only with people who have the party code.

Source and releases: https://github.com/Plussit/RootBeat

## Install

Android 11 or newer. Download `rootbeat-1.0.0.apk` from this repository's Releases page and install it. If Android asks, allow installs from your browser.

The 1.0.0 release APK is package `com.rootbeat.player`, version code 100. SHA-256: `c628065c9f72abbbdd8ec3855c2a38f29751a60db54b05745969c7427ce0e27f`

## Build

```bash
./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=false
```

The release is signed with the keystore published next to the APK. That key is for sideload updates of this build. Replace it before any store listing.

## License

RootBeat is free software under **GPL-3.0-or-later**. Copyright remains with the Pixel Music contributors and the upstream authors named in [PROVENANCE.md](PROVENANCE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

This program is distributed without warranty. See [LICENSE](LICENSE).

RootBeat is not affiliated with Google, YouTube, Spotify, or the upstream Pixel Music maintainers.
