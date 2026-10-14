# BeatPad

A pad-based beat maker for Android, in the spirit of classic MPC-style samplers.

## Install on your phone
1. Open the **Releases** page of this repo on your phone (or the newest "BeatPad build" link).
2. Tap **BeatPad.apk** to download it, then open it.
3. If Android asks, allow your browser to "install unknown apps", then tap **Install**.

Every push to `main` builds a new APK automatically (see the Actions tab).

## Features
- **16 pads** with a built-in synthesized drum kit; multi-touch, MPC layout (pad 1 bottom-left)
- **16-step sequencer** with tempo, tap tempo, swing, metronome and live REC (tap pads while it plays)
- **Get sounds from anywhere**
  - Import audio files, or the audio from a video
  - Record with the mic
  - Capture what other apps are playing (Android 10+)
  - "Record through speaker" fallback for apps that block capture
  - Silence is trimmed automatically; trim, reverse, normalize and undo per pad
- **Per-pad** volume, pitch (±24 semitones) and choke groups
- **Beatbox → Drums**: detects kicks, snares, hats and open hats in your beatbox, guesses your tempo, and writes the pattern; or keeps your exact timing as a loop; or uses your own voice sounds
- **Hum → Instrument**: tracks the pitch of your humming and replays it as Bass, Keys, Synth, Pluck, Pad or Bell
- **Export** 1–8 bars as a WAV file
- Your kit and pattern are saved automatically

## Build it yourself
Open the folder in Android Studio and press Run, or run `gradle assembleRelease` with Gradle 8.7 and JDK 17.
