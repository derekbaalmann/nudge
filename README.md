# Nudge

Voice reminders for an ADHD brain. Tap once, say *"pick up nails at 4pm tomorrow"* or
*"remind me to get gas when I get in the van"*, and it's set. Phone (Android) + watch (Wear OS).

Everything runs on the device. Speech is recognized **offline** on the phone with NVIDIA
Parakeet TDT 0.6B via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), and the sentence is
understood by a rule-based parser. Nothing is sent anywhere.

Built for one person's setup (Galaxy S22 Ultra + Galaxy Watch Ultra) and shared as-is in case it
helps someone else. It's a sideloaded personal app, not a Play Store release.

## How it works

1. **Start recording, instantly.** Phone: home-screen widget, Quick Settings tile, long-press app
   shortcut, or the *Nudge Record* launcher entry (map it to the side key). Watch: the Nudge tile,
   a watch-face complication, or the *Nudge Record* app on the Home-key double-press.
2. **Talk until you're done.** It never stops on a pause; a waveform shows it's hearing you. Tap
   **Done talking** (or anywhere on the watch).
3. **5-second confirm** (about 0.7 s after you stop): "Get gas · Connect to SYNC" with
   **Confirm / Edit**. Do nothing and it saves. If no time or place was understood, the card says
   so and it saves to the Inbox flagged "needs a when". Walking away mid-way also saves. A thought
   is never lost.

## Speech

One model, run once over the whole recording after you stop: Parakeet TDT 0.6B (int8, ~630 MB).
On a test set of fast and noisy reminders it got 0–2% of words wrong, versus 27–39% for the small
streaming model used at first, and never split "tomorrow" into "to morrow". On the S22 Ultra it
takes ~0.7 s for a 5 s reminder. It is too big for the APK, so `tools/install.ps1` copies it to
`/sdcard/Android/data/app.nudge/files/models/parakeet-0.6b/` once (uninstalling the app deletes it).
A small foreground service ("Nudge is ready"; Settings › Keep speech ready) keeps it loaded,
using ~1.2 GB of RAM, because loading takes ~3 s in the foreground and ~8 s in the background.

The watch runs no model: it streams the mic to the phone over the Wear Data Layer while you talk
and gets the result back. If the phone isn't reachable, the recording is queued and the phone
transcribes and saves it when it reconnects.

## Triggers

| Say | Trigger |
| --- | --- |
| at 4pm · at four thirty · tomorrow at 9 · on Monday · October 15th · tonight · after lunch | Time |
| in 20 minutes · in an hour and a half · 10 minutes from now | Time |
| every day at 8 · every weekday · every Tuesday and Friday at 7pm · first of every month · every 2 hours | Repeating |
| when I get home · when I get to Home Depot · next time I'm at the shop · when I leave | Place (arrive / leave) |
| when I get in the van · when I connect to home wifi · when I get out of the truck | Bluetooth / wifi |
| when I plug in my phone · when I unplug | Charging |
| with a 30 minute warning · and warn me 10 minutes before | Heads-up before a timed reminder |

**Aliases:** Settings › Places & connections. Add the car stereo (*SYNC*) with the other names
*van, work truck, work vehicle*. When it hears a name it doesn't know ("post office") it asks which
saved thing that is, and remembers the answer as a new alias.

## Reminders that won't let go

- Notifications have **Done**, **Sleep 15m**, **Sleep 1h** (both durations configurable). Tap the
  notification for more: 5 min to 4 hr, tonight, tomorrow, *until I get home / to the van*, *until I plug in*.
- **Nag mode** (on by default): re-alerts every 5 min until Done or Sleep.
- **Strong watch buzz**: three long hard pulses when something fires.
- **Heads-up** before timed reminders.
- **Evening review** (8 PM): what's still open, plus Inbox items to give a *when*.
- **History** tab: everything added, fired, slept and done, with Undo.
- **Multi-select**: long-press a reminder in Upcoming or Inbox, tap more, then mark them all done or
  delete them; an Undo bar puts them back.

## Build & install

Needs the Android SDK (Android Studio) and a **JDK 17–21**: AGP 8.7 refuses JDK 25, which current
Android Studio bundles. Set `JAVA_HOME`, or add these to `local.properties` (not committed):

```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
nudge.javaHome=C:/Users/<you>/.jdks/jdk-21.0.12.1+1   # used by install.ps1
nudge.buildDir=C:/Users/<you>/AppData/Local/NudgeBuild  # optional: build outside the project
```

The scripts are PowerShell (Windows):

```powershell
.\tools\fetch-models.ps1        # one time: speech library + model (~700 MB, from the sherpa-onnx releases)
.\tools\install.ps1             # build release, install on the phone over USB, copy the model over
.\tools\install.ps1 -Watch 192.168.1.50:41234 -SkipPhone   # install on the watch
```

Release builds are signed with your local debug key, which is fine for sideloading; the phone and
watch apps must be built on the same machine so their signatures match (the Wear Data Layer requires it).

**Watch (Galaxy Watch Ultra):** Settings › About watch › Software › tap *Software version* 5× ›
Developer options › turn on *ADB debugging* and *Wireless debugging* › *Pair new device*. On the PC:
`adb pair <ip:port> <code>`, then use the *IP address & port* shown on the wireless debugging screen
with `install.ps1 -Watch`. Phone and watch builds must come from the same PC (same signing key).

Parser tests: `.\gradlew.bat :core:test`.

## First-run checklist (Samsung phone)

- Allow microphone, notifications, location **"Allow all the time"**, nearby devices.
- **Battery › Unrestricted** for Nudge (Samsung otherwise puts it to sleep and reminders arrive late).
- Add the widget and the Quick Settings tile from Settings › *Get to it fast*.
- Side key: Settings › Advanced features › Side button › Double press › Open app › **Nudge Record**.
- Watch: Settings › Advanced features › Customize buttons › Double press › **Nudge Record**.

## Layout

| Module | What |
| --- | --- |
| `core/` | Plain Kotlin: models, the parser (`ReminderParser`), repeat math, phone↔watch protocol. Unit-tested. |
| `shared/speech/` | Mic capture and the Parakeet recognizer (phone only). |
| `mobile/` | Phone app: capture overlay, reminder engine (alarms, geofences, wifi/bluetooth/charger), notifications, widget, tile, main app. |
| `wear/` | Watch app: streams the mic to the phone (no model on the watch), confirm/edit, list with Done/Sleep, tile, complication, strong buzz. |

Data is a few JSON files in the app's private storage (`reminders.json`, `targets.json`, `log.json`, `settings.json`).

## Status

Tested on a Galaxy S22 Ultra (Android 16) and Galaxy Watch Ultra (Wear OS, 32-bit userspace):
voice capture on both, the watch-to-phone round trip, transcription, parsing, the confirm card,
timed reminders with Done/Sleep, and multi-select. Not yet verified on real hardware: Bluetooth,
wifi and location triggers, the watch tile and complication, and the watch's offline queue.
English only.

## Credits & licenses

Nudge's own code is under the [MIT License](LICENSE). It relies on, but does not include:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) for on-device inference.
- [NVIDIA Parakeet TDT 0.6B v2](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) (CC-BY-4.0),
  using the int8 ONNX export published in the sherpa-onnx releases.

`tools/fetch-models.ps1` downloads both from the official sherpa-onnx GitHub releases.
