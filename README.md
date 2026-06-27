# Music Announcer

An Android app that announces the artist and song title of currently playing music using Text-To-Speech.

## Version

- Current version: `1.1.0`
- Debug APK artifact: `MusicInfo-v1.1.0-debug.apk`

## Features

- Reads current music metadata from active media sessions.
- Supports Spotify and YouTube Music by default, with selectable allowed apps.
- Supports mixed Korean/English announcements inside a single utterance.
- Splits mixed-language text into Korean/English segments and switches TTS engines sequentially.
- Lets users select separate TTS engines for Korean and English.
- Supports English locale variant selection: US or UK.
- Provides one-button Korean + English test playback.
- Uses a global 1-second announcement cooldown to prevent duplicate announcements.

## Setup Instructions

1.  **Open Project**: Open this directory in Android Studio.
2.  **Sync Gradle**: Allow Android Studio to sync and download necessary Gradle dependencies.
3.  **Run**: Deploy the app to your Android device or emulator.
4.  **Permissions**:
    *   Launch the app.
    *   Tap **"Enable Notification Access"**.
    *   Find **"Music Announcer"** in the list and enable it.
    *   Allow the permission warning.
5.  **Configure Apps**:
    *   Tap **"Choose Apps..."** and select music apps.
    *   Defaults: Spotify (`com.spotify.music`) and YouTube Music (`com.google.android.apps.youtube.music`).
6.  **Configure TTS**:
    *   Select a Korean TTS engine.
    *   Select an English TTS engine.
    *   Select the English variant, US or UK.
    *   Adjust speed and volume.
    *   Tap **"Save Settings"**.

## Usage

*   Play music in one of the allowed apps.
*   The app announces the artist and title when metadata changes.
*   If the announcement contains both Korean and English, the app switches between the configured Korean and English TTS engines while speaking.
*   There is a 1-second global cooldown to prevent repeated announcements for the same update.

## Build

```powershell
& "C:\Users\USER\.gemini\antigravity\scratch\tools\gradle-8.4\bin\gradle.bat" assembleDebug
```

The versioned debug APK is generated at:

```text
app/build/outputs/apk/debug/MusicInfo-v1.1.0-debug.apk
```
