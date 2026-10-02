# Gaming Mode (Android, Kotlin)

## Build with GitHub only (no laptop)
Easy way, from the phone browser (use "Desktop site"):
1. Create a new GitHub repository.
2. Add file > Upload files: upload `GamingMode.zip` (the zip as-is).
3. Add file > Create new file. Name it `.github/workflows/build.yml` and paste the contents of `build-from-zip.yml`. Commit.
4. Open the Actions tab, open the latest run, wait ~5-8 minutes, then download the artifact **GamingMode-APK** (a zip containing app-debug.apk).
5. Install the APK. Allow "install unknown apps" when asked.

If you unzip it yourself and upload the folder contents, the bundled `.github/workflows/build.yml` builds it directly.

## After installing
Open the app > Enable Gaming Mode > turn on every permission > Start gaming mode.
On Android 13+, accessibility may be greyed out: App info > ⋮ > Allow restricted settings.

## Notes
- Groq model names change. Edit them in AI Corp > ⚙ (vision model, coding model).
- DPI changing needs Shizuku and is not in this build.
- The sensitivity booster changes pointer speed only (mouse/gamepad).
- Automating taps in online games can break their rules and get accounts banned.
