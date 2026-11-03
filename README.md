# Sound Setter (Android)

Set **any audio file** as a system sound — call ringtone, SMS/notification sound,
alarm sound — from inside one app. The app reads the current values from Android's
own sound settings, publishes your file where the system expects it, writes the
setting, and **verifies by reading it back**. If anything fails, it tells you
exactly which step failed and how to fix it.

**APK:** [`dist/SoundSetter-v1.0.apk`](dist/SoundSetter-v1.0.apk)
(min Android 7.0 / API 24, targets Android 14 / API 34, self-signed with a local
key in `keystore/` — re-sign with your own key if you distribute it)

## What it does

| Row | Setting written | Used for |
|---|---|---|
| Call ringtone | `Settings.System.ringtone` | Incoming calls (system-wide) |
| Notification sound | `Settings.System.notification_sound` | Received SMS/texts and app notifications that use the default sound |
| Alarm sound | `Settings.System.alarm_alert` | Clock app alarms |
| SIM 1 / SIM 2 ringtone (Xiaomi) | `ringtone_sound_slot_1/2` + global | MIUI/HyperOS per-SIM ringtone, which otherwise overrides the global one |

Each row shows the **current** sound (read back from the system settings /
MediaStore, including the MIUI per-SIM keys), has a **▶ Preview** button, and a
**Choose file** button that opens the system document picker (every audio format
Android supports: MP3, WAV, OGG/Opus, FLAC, M4A/AAC, AMR, 3GP, MIDI, AIFF, …).

## How it works (the apply pipeline)

Android silently ignores most naive attempts to set a custom sound, so the app
uses a researched, multi-stage pipeline:

1. **Format detection** — provider MIME, then an extension table, then
   byte-sniffing (ID3/`0xFF`, RIFF/WAVE, OggS, fLaC, ftyp, #!AMR, MThd).
   *Why:* `RingtoneManager.setActualDefaultRingtoneUri()` performs a **silent
   no-op** if `ContentResolver.getType()` fails or the type isn't `audio/*` —
   this is the #1 cause of "app did nothing" reports.
2. **Publish into MediaStore** (Android 10+): insert into
   `VOLUME_EXTERNAL_PRIMARY` with `RELATIVE_PATH = Ringtones/ | Notifications/ |
   Alarms/`, copy bytes through `openOutputStream`, `IS_PENDING=1 → 0`, and set
   `IS_RINGTONE / IS_NOTIFICATION / IS_ALARM` flags (the columns the system
   ringtone picker filters on). Android 7–9: write the public folder and run a
   media scan, with a classic `DATA`-path insert as fallback.
   *Why:* system settings must point at a **`content://media/...` URI** —
   raw `file://` paths are the classic "worked, then reverted on reboot" bug.
3. **Write the setting** via `RingtoneManager.setActualDefaultRingtoneUri()`
   (requires the "Modify system settings" special permission, which the app
   requests through the system screen), **plus the Xiaomi per-SIM keys**
   `ringtone_sound_slot_1/2` when they exist — on MIUI these override the global
   ringtone, which is why setting only the global key appears to do nothing on
   dual-SIM Xiaomi/Redmi/Poco phones.
4. **Verify by reading back** (normalized comparison that ignores per-user
   `content://0@…` prefixes and the `external_primary` vs `external` alias).
   If it didn't stick: retry with a direct `Settings.System.putString()`, retry
   `RingtoneManager` again, and then — if the user enabled **Elevated (ADB)
   mode** — run `settings put system <key> <uri>` as shell uid 2000 through
   **Shizuku**, which bypasses OEM write restrictions entirely.
5. **Warnings** for soft problems (e.g. a per-SIM key that kept its own value)
   and a playability check (`openAssetFileDescriptor`) of the published file.

## Error handling

Every step appends to a pipeline log. On failure you get: the human reason, a
concrete "what to do" list (grant the permission, MIUI-specific steps, elevated
mode, system-picker fallback), a **Details** dialog with the full technical
report (step id, exception stack trace, every step's outcome, the URI read back)
that can be copied to the clipboard, and — when the file is already in
MediaStore — a one-tap **system sound picker** fallback pre-loaded with your
file so the privileged system UI applies it.

## Optional: Elevated (ADB) mode (Shizuku)

Not required. For devices that hard-block the normal path, the app integrates
[Shizuku](https://shizuku.rikka.app/): install Shizuku, start it via wireless
debugging (Android 11+, no PC), authorize this app, and the pipeline automatically
retries blocked writes as shell. Root is not needed. Everything else works
without Shizuku and without any other app.

## Building from source

The project is pure Java + framework Views (no AndroidX/Gradle), built with the
standalone Android SDK — **[REBUILD.md](REBUILD.md) is a complete,
reproducible rebuild guide** (exact toolchain download URLs, integrity checks,
step-by-step commands, expected verification output, and an AI-friendly
file-by-file description).

```bash
# One-time toolchain setup is fully documented in REBUILD.md §2:
# - Temurin JDK 17        -> $HOME/toolchain/jdk-*
# - Android SDK (34, bt 34.0.0) -> $HOME/android-sdk
# - Shizuku 13.1.5 AARs + androidx.annotation -> $HOME/toolchain/shizuku-libs

./build.sh        # aapt2 → javac → d8 → zipalign → apksigner (+ verify + badging)
```

Source layout:

```
app/src/main/AndroidManifest.xml
app/src/main/res/drawable/ic_launcher.xml
app/src/main/java/com/tools/soundsetter/MainActivity.java     # UI, permission & picker flow
app/src/main/java/com/tools/soundsetter/SoundApplier.java     # the apply pipeline + verification
app/src/main/java/com/tools/soundsetter/SoundEvent.java       # the system events (incl. MIUI slots)
app/src/main/java/com/tools/soundsetter/ShizukuBridge.java    # optional elevated shell writes
```

## Sources & credits

Research this app is built on (all links verified):

**Android platform behavior**
- AOSP [`RingtoneManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/media/java/android/media/RingtoneManager.java) — default-URI storage and the silent no-op this app defends against
- [`RingtoneManager` API reference](https://developer.android.com/reference/android/media/RingtoneManager)
- [`Settings.System`](https://developer.android.com/reference/android/provider/Settings.System) (`canWrite`, `putString`, ringtone keys)
- [MediaStore: files in shared storage](https://developer.android.com/training/data-storage/shared/media) — volumes, `RELATIVE_PATH`, `IS_PENDING`, sound collections
- [Request runtime permissions](https://developer.android.com/training/permissions/requesting) — `ACTION_MANAGE_WRITE_SETTINGS`, `READ_MEDIA_AUDIO`

**Shizuku (optional elevated mode)**
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) — <https://shizuku.rikka.app/> (Apache-2.0)
- [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) — client libs 13.1.5 from [Maven Central](https://repo1.maven.org/maven2/dev/rikka/shizuku/api/13.1.5/api-13.1.5.pom), license: [Apache-2.0](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)

**Xiaomi / MIUI / HyperOS**
- [xiaomi.eu: can't add custom ringtone / per-SIM key override](https://xiaomi.eu/community/threads/fix-cant-add-no-custom-ringtone-locale-and-online-playing-possible.61462/) — source for the `ringtone_sound_slot_1/2` override behavior

**Toolchain**
- [Eclipse Temurin JDK 17](https://adoptium.net/) — <https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse>
- [Android command-line tools](https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip) (sdkmanager → `platforms;android-34`, `build-tools;34.0.0`)
- [`androidx.annotation:1.3.0`](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar) (compile-time only)

Credits: **Shizuku** and **androidx.annotation** are by their respective
authors under Apache-2.0 and are credited here as required; Android platform
behavior follows AOSP and the official Android documentation linked above.
Full rebuild + credit details: [REBUILD.md](REBUILD.md).

## Notes & honest limitations

- Android only offers *one* systemwide default notification sound. If an
  individual app (e.g. a messaging app) has pinned a custom sound on one of its
  own notification channels, that per-channel choice wins — the same applies
  when you change the sound in the system Settings app.
- On MIUI/HyperOS, if Settings → Sound & vibration still shows the old tone
  afterwards, open that page once and re-select the new entry (MIUI caches its
  sound list), or use Elevated mode.
- The APK here was compiled, dexed, signed and signature-verified in this
  workspace; it could not be launched on a device/emulator from this environment,
  so the on-device behaviour should be smoke-tested after install.
