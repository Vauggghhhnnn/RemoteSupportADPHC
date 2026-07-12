# ADPHC Remote Support — Android App

This is a **native Android app** that fixes the screen-share problem on Android.
Screen capture (`getDisplayMedia`) is disabled by Chromium on every Android
browser, so no website — however it's packaged — can share the screen on
Android. This app uses Android's real `MediaProjection` API instead, and talks
to your existing signaling server (`remotesupportadphc.onrender.com`) using
the exact same Socket.IO events your `index.html` already uses. Your IT
dashboard on the web needs **no changes**.

## How to build the APK (no Android Studio needed)

1. Copy the `android/` folder and `.github/workflows/build-apk.yml` into the
   root of your `RemoteSupportADPHC` GitHub repo, and push to `main`.
2. GitHub Actions will automatically build the APK. Watch it under the
   **Actions** tab of your repo.
3. When the run finishes (green check), open it and download the
   `RemoteSupportADPHC-debug-apk` artifact — that's a zip containing
   `app-debug.apk`.
4. Send that `.apk` to the Android phone (email, Drive, etc.), open it, and
   allow "install from unknown sources" when prompted. This is a normal debug
   build so Android will warn about that — that's expected for an app not on
   the Play Store.

## Using the app

1. Open the app, type a name, tap **Join Session**.
2. Tap **Share Screen** → Android's system dialog asks to confirm screen
   capture (a real OS-level dialog, this always works, unlike the browser).
3. Your screen now appears in the IT web dashboard exactly like a desktop
   user would.
4. A persistent notification shows while sharing (Android requires this for
   background screen capture) — tapping "Stop Sharing" or swiping the
   notification away stops it.

## If the Actions build fails

Open the failed run's log and paste me the error — the most likely first-time
issues are dependency-version mismatches (WebRTC/Socket.IO library versions
move fast) which are a one-line fix in `app/build.gradle`.

## Notes / things you may want to change

- `SERVER_URL` is hardcoded in `MainActivity.kt` to match your `index.html`'s
  `CONFIG.serverUrl`. Update both together if you ever move servers.
- There's no IT password login screen in this app — this app is only the
  "user/share screen" side. IT still uses the existing web dashboard
  (`index.html?it=1`).
- This is a debug build (fine for internal support-tool use). For wider
  distribution you'd want a signed release build — say the word and I'll add
  a signing step to the workflow.
