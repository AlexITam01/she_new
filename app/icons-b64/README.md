# Launcher icons (base64)

The 12 launcher-icon PNGs live here as `.png.b64` text files because the
GitHub API used to push this repo cannot upload binary files — it stores
base64 as literal text, double-encoding the PNG.

At build time, the `decodeIcons` Gradle task in `app/build.gradle.kts`
(decodes, via `Base64.getMimeDecoder()`) restores them into
`app/src/main/res/` before resources are merged, so the built APK has the
real Furret PNGs:

- `drawable/furret.png` — splash image (`R.drawable.furret`)
- `drawable/ic_launcher_foreground.png` — adaptive-icon foreground
- `mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` and
  `ic_launcher_round.png`

The decoded PNGs are build outputs and are not committed. Do not delete
these `.b64` files, or the build will have no launcher icon.
