# Historical design notes — not setup instructions

This chronological record includes superseded behavior and internal design references.
Use the root README for current build, setup and Nolee AI integration instructions.

## Kiosk companion choice

Before enabling kiosk, ask the owner which companion apps this launcher should open. Offer Nolee
Phone (`ai.nolee.phone`), SMS (`ai.nolee.sms`), Camera (`ai.nolee.camera`), Gallery (`ai.nolee.gallery`)
and Files (`ai.nolee.files`) as the default set. If the owner already has preferred apps, use their
set instead—do not silently replace it or grant extra companions. Install/check the chosen apps,
request their companion approvals, and only then enter kiosk; otherwise those launch buttons can
be blocked. Keep existing approved companions unless the owner asks to remove them.

`android/tools/setup_device.sh` defaults to the five Nolee packages. Set `NOLEE_COMPANIONS` to a
space-separated list of approved replacement packages before running it, or to an empty string
for no companions. In a fork, also update `AppEntry` package mappings and manifest `<queries>` to
match the selected apps; changing an allowlist alone does not change the launch buttons.

## Native Cloud AI persona

The side button retains the original Home/Watch shortcut and returns the persona to Watch.
Long-hold the side button opens Power. Hold the full-screen watch face to open the persona, or use
Home → Nolee AI → Start Nolee AI. The settings page shows live status, validity and shared daily/monthly
usage, plus app-local spoken-answer and voice preferences, without exposing key controls. Status is
fetched on settings-page entry/manual refresh, never as an overlay-opening preflight.
The persona records a spoken question, streams a Cloud answer and plays the returned
audio, then listens for a follow-up. Long-touch the camera lid (F6) to toggle the transcript;
screen/lid taps do not toggle it. Hold the ball to interrupt and ask again, with haptic feedback.
Its listening edge previews immediately on touch; release early or move to cancel and reverse the
preview without starting a new recording. Swipe left/right to cycle front, upper-left, right and
top camera views; up selects top and down selects front. Listening/thinking labels move right when
the camera puts the ball left. Transcript body text is enlarged independently of its heading.
Leaving or pausing the app cancels recording,
the request and playback. Home's Vosk Voice Command remains a separate offline command flow.

The implementation is `android/app/src/main/java/ai/nolee/brandedlauncher/CloudAi.kt`. Before each
question it reads `/system/nolee/credentials/nolee-key` through the app's owner-approved root
access. The raw key stays in process memory: do not export it for this app, embed it in an APK,
log it or add it to source or build configuration. Enable this app under the stock Launcher's
System → Access → Root access, grant microphone permission, and activate Nolee AI in the stock
Launcher. Only that Launcher handles activation, rotation and revocation. Rotation is picked up
on the next question because the key is read again.

The persona sends `mode: "general"` and the app-defined `APP_PROMPT`, with up to five completed
conversation turns and nonempty owner Profile fields. The prompt describes this launcher, the ball
persona and its navigation/gestures. It does not include screenshots, camera input or live UI state.
This context is sent to Nolee Cloud to answer
the question; conversation history lasts for the persona session. Both apps share the unit's
allowance. The persona uses spoken answers with Serena by default and enables Cloud tool selection
(not web search). `CloudCommands.kt` advertises a bounded catalogue covering all Vosk actions:
apps/pages, radios, brightness, sound, automatic clock/time zone and profile-field editing.
The server's `queue_device_command` function returns up to three validated `device.actions` in SSE.
The app validates these again and runs the existing animated command executor only after the
successful stream and spoken reply finish. Errors/cancellation discard queued actions. The model
is told actions are queued, not already successful; actual controls remain subject to device permissions.
This is deferred local dispatch, not a live tool-result round trip back into the model.
Edit the prompt/request in `CloudAi.kt` when forking the example.
No provider key is required to build either debug or release APKs.

Open `index.html` in a browser. This is an interactive design prototype, not an Android APK. All connection, battery, storage, capture, calling, and kiosk states are simulated. No device changes are made.

## Visual direction

Based on Boot Art 05 (Peace / Boot Shell, portrait 410 × 502) and 06 (landscape 502 × 410). Reuses the original peace PNG and Spline Sans Mono font, with near-black grid, white text and mint #83f5d0. Important controls sit within the rounded display perimeter. Both orientations can be explored from the preview. Home includes a personal greeting, Camera, Files, Gallery, Phone, SMS, and persistent System access.

The referenced Boot Art currently lives at `Software/Custom_Apps/Boot_Art`; this design is saved at the exact new path requested by the owner.

## Navigation and states

- App shortcuts demonstrate native handoff with illustrative companion screens. They are not proposed replacements for existing native app interfaces.
- The whole bottom bar is a single Home button, which clears the navigation stack. There is no on-screen Back; Escape still goes back in the browser, and the Home key mirrors Home.
- System contains Wi-Fi, Bluetooth, Display, Sound, Type system, and Vitals, matching the categories in Harness `SystemSettings.kt`. Launcher management adds kiosk state, companion allowlist, Home destination, and an owner access concept.
- Network and pairing rows describe their native continuation. Sliders retain sample values for the current preview session. The font selector changes preview typography.
- Owner verification is explicitly simulated. The button demonstrates the post-verification kiosk transition; it must not become the production authentication mechanism.
- Camera simulates capture, Gallery shows sample artwork, Files shows sample paths, and Phone simulates a call without accessing native services.

## Android implementation handoff

Use the existing Nolee kiosk/provisioning infrastructure, with this app as its configured launcher and Android Home destination. Verify the existing management mechanism before implementing enforcement; hiding system bars alone is not kiosk security.

Launch installed companions by their verified application IDs: `ai.nolee.camera`, `ai.nolee.files`, `ai.nolee.gallery`, `ai.nolee.phone`, `ai.nolee.sms`. Resolve launch intents through PackageManager; provide an unavailable state and owner repair path if a package is missing. Keep native permissions and privacy flows within the relevant companion.

Allow the launcher and the five companions in the kiosk policy. Confirm native companion Back, Home, and hardware button behavior returns to this launcher. Maintain the session across companion exits and process recreation, and restore Home on boot through the existing provisioned flow. System controls should remain in the managed shell; privileged operations must use the established platform service and permission boundaries. Exit kiosk requires the production owner authentication mechanism.

Before an APK release, validate display scaling and safe areas on hardware in both orientations; battery/network accuracy; font scaling; companion launch and return; boot recovery; missing app handling; restricted external intents; incoming call behavior; and authenticated kiosk exit. This design phase does not implement or deploy those behaviors.

## Source references

- `Software/Custom_Apps/Boot_Art/index.html`, studies 05 and 06.
- `Software/Custom_Apps/Boot_Art/assets/nolee-peace-design.png`.
- `Software/Custom_Apps/Harness_App/app/src/main/java/io/kungcorp/nolee/harness/SystemSettings.kt`.
- Each native companion's `app/build.gradle.kts` for application IDs.

## Rolling app selector refinement

Only the Home app-button area changes. Greeting, slogan, status header, session line, and footer retain their prior layout. Five native companions roll vertically through a fixed mint selection frame. The centered app opens on tap; tapping either visible neighbor selects it first, following Harness’s select-then-launch behavior. Swipe, mouse wheel, previous/next controls, and keyboard arrows move the loop; Enter opens the focused app. The wheel wraps in both directions and remembers selection after returning Home. Reduced-motion preferences disable settling animation. SMS opens a sample inbox/conversation with no sending behavior.

### Landscape dock

The highlighted app now rolls out of the right-side drum into a 210 × 62 launch button below the slogan and above Session Ready. A continuous mint trace joins the launch slot to the peripheral wheel. It is the same moving button, not a duplicate shortcut. The surrounding text and footer positions, portrait layout, and selection/launch behavior are retained.

## Home motion and greeting

Entering Home staggers in the tag, greeting, slogan (tracking in), app drum (spinning in from just above the remembered app), session line and bottom bar. The greeting uses the Boot Art 05/06 typewriter (145 ms per character portrait, 83 ms landscape) and cycles through Hello, a time-of-day Good Morning/Afternoon/Evening, Welcome back, and three open questions. Each phrase blinks its cursor five times, fades the cursor out, holds, then erases into the next; long phrases shrink to fit their column. Idle motion: a sheen across the selection frame, a pulsing notch, a breathing icon glow and nudging launch arrow on the selected app, bobbing rail arrows, a pulsing session dot and a mint sweep along the bottom bar. Taps flash the launching app, compress the rail arrows and bar label, and pop the counter.

On Home the bottom bar is **Voice Command** with the AI sparkle (from Neumorphic Launcher), shimmering and twinkling; the browser prototype opens a simulated command screen. On every other screen the bar is Home.

## Peace artwork entrance

Entering Home replays the Nolee website hero effect on the peace artwork: a bottom-up masked raster reveal with fade, lift and de-blur, a blurred mint aura that reveals then breathes, a slow float, and a mint glow pulsing over the drawn watch. Re-rendering Home in place (greeting edits, toggles) does not replay it. Reduced-motion preferences show the artwork static.

## Time / Peace watch face

Watch is the sixth launcher entry, after SMS, followed by System as the seventh, and opens an internal watch-face screen through the same rolling selector. The five native companion packages remain unchanged. Watch uses a clock icon and WATCH FACE subtitle, with a dynamically sized 06-item counter. It is called Watch, not Time, so it does not read as System's Date &amp; time page — `Page.Watch` in the code, for the same reason.

The watch face displays live 24-hour browser-local time, seconds with a mint 60-tick progress ring, weekday, date, and resolved timezone. Portrait centers the digital clock within the ring; landscape places the clock beside it. The peace artwork fades out when Time opens and fades back in on exit, while a slightly stronger grid fades in; the header, Home bar, and existing Home layout remain. The header clock now also uses live local time. Clock updates pause while the page is hidden and refresh immediately on visibility restoration. All other device readings remain sample data. No native Time APK is created in this design phase.

## Native Compose app (`android/`)

`android/` is the watch app, `ai.nolee.brandedlauncher`, rebuilt natively in Jetpack Compose. It replaced a WebView wrapper around this prototype, which was held back by the watch's Chrome 79 WebView. The prototype in this folder remains the design reference; the app ports its portrait layout in the same 410 × 502 design coordinates.

- **Scaled to the lens.** `Stage.kt` maps design coordinates onto the 408 × 502 panel so the prototype's rim (6 px inset, 398 px wide) lands on the measured safe-zone outline (2 px inset, 112 px corner radius, from `Nolee_Launcher/.../DisplaySafeZone.kt`). That is a uniform 404/398 ≈ 1.5% scale-up, with the rim drawn on the outline itself. Text ignores the system font scale so nothing is pushed through the curve. `Nolee_Launcher/verify_safe_zone.ps1` passes on Home, System, Vitals, Time and Ask AI.
- **Motion budget.** Entering Home plays the entrance, the drum spin, the peace reveal and one typed greeting: the next phrase on each entry, five cursor blinks, then the cursor fades. Idle loops may only start a cycle until 10 s after the reveal ends, and each finishes on its resting pose; after that Home draws no frames. Measured on the watch: 7 ms median and 12 ms 90th-percentile frames during motion, 0 frames at rest.
- **Real data and controls.** Companions open their `ai.nolee.*` apps. The header clock and battery, kiosk state, Wi-Fi and Bluetooth toggles, brightness, media and ring volume, and Vitals (battery, temperature, storage, model) are live. Leave kiosk calls the Nolee Launcher's `exit_kiosk`.
- **Not yet ported.** Landscape (the activity is locked to portrait) and the Type system page.

Build from an agent shell with `android/tools/agent_build.sh` (debug by default).

### Watch and power controls

The Android app keeps only the Peace digital face. Tapping its compact dial expands
it immediately to the full-screen idle layout; touching the full-screen face wakes
the normal page, while holding the full-screen dial opens the persona. The former
Perpetual mechanical face and its selection carousel are no longer part of the UI.
The separate `../Watch_Face` design reference and its exporter are untouched.

Holding the F9 side button opens a Power page from any screen with large Restart
and Shut down slide controls. They fade, rise and scale into place in sequence.
Drag a handle right and release in the final 8% of its travel to confirm; taps,
cancelled drags and partial slides do not act, and the handle returns smoothly.
Both controls lock while a power command is running.
A short press still swaps Home and Watch (or returns from the
persona); a short press during Voice Command cancels listening. The System drum's
selected card alone has a taller frame, icon and type; neighboring cards retain
their original dimensions.

### Agent setup: required permissions and access

Installing the APK is not enough. The app needs grants that nothing requests on install:

Agents: after installing, configure the permissions below on the owner's selected Nolee DevKit
Ultra. Always use an explicit ADB serial. Request the owner's approval before enabling root,
location services or changing their kiosk configuration. `adb install -r` preserves app data;
do not uninstall just to update the app.

- **Android grants:** `pm grant` for `WRITE_SECURE_SETTINGS` (vitals measurement mode), `BODY_SENSORS` (the vitals sensors: without it Android refuses to enable them and no reading ever arrives), `RECORD_AUDIO` (Ask AI), and fine and coarse location (Wi-Fi and Bluetooth scans). Also `appops … WRITE_SETTINGS allow` (brightness).
- **Nolee Launcher grants:** root (the reset-surviving Nolee key, privileged controls and their `su` fallback), and the five companions (Camera, Files, Gallery, Phone, SMS). Strict kiosk blocks any app that is not an approved companion. Root is not an Android runtime permission: there is no `pm grant … ROOT` command.
- **Kiosk:** the app as primary app, then kiosk.

For an owner-approved full kiosk setup, enable AI agent access (ADB), install the five companion
apps, build the APK, then run the bundled script from a Bash shell (Git Bash works on Windows):

```bash
android/tools/setup_device.sh [SERIAL]
```

It leaves kiosk first, because the Launcher grants need kiosk off, and it stops on the first refusal. Re-run it after an uninstall and reinstall or a factory reset: those clear the grants. An `adb install -r` update keeps them.

For permission setup without choosing a kiosk primary app, replace `SERIAL` with the selected
device's serial and run these commands. Leave kiosk before changing Launcher-managed access.

```text
adb -s SERIAL shell pm grant ai.nolee.brandedlauncher android.permission.RECORD_AUDIO
adb -s SERIAL shell pm grant ai.nolee.brandedlauncher android.permission.BODY_SENSORS
adb -s SERIAL shell pm grant ai.nolee.brandedlauncher android.permission.ACCESS_FINE_LOCATION
adb -s SERIAL shell pm grant ai.nolee.brandedlauncher android.permission.ACCESS_COARSE_LOCATION
adb -s SERIAL shell pm grant ai.nolee.brandedlauncher android.permission.WRITE_SECURE_SETTINGS
adb -s SERIAL shell appops set ai.nolee.brandedlauncher WRITE_SETTINGS allow
adb -s SERIAL shell settings put secure location_mode 3
adb -s SERIAL shell content call --uri content://io.kungcorp.nolee.launcher.state --method set_root_access --arg ai.nolee.brandedlauncher --extra enabled:b:true
```

Enable companions individually with `set_companion --arg PACKAGE --extra enabled:b:true` using
the same provider URI. Packages are `ai.nolee.camera`, `ai.nolee.files`, `ai.nolee.gallery`,
`ai.nolee.phone` and `ai.nolee.sms`. Inspect every provider response: `ok=false` is a refusal even
when the shell command exits successfully. Root grants can also be managed in Nolee Launcher →
System → Access → Root access. Do not use a device-wide root bypass.

These grants match this app's Android 9 / API 28 manifest. Internet, vibration, wake lock, radio
state/change and audio-settings permissions are manifest permissions; they need no `pm grant`.
The app hands calling, messaging, camera and files to companion apps, which need their own setup;
do not grant unrelated camera, contacts, storage or phone permissions to this package.

Before handing off, check `dumpsys package ai.nolee.brandedlauncher` for the five explicit grants,
`appops get ai.nolee.brandedlauncher WRITE_SETTINGS`, and `settings get secure location_mode`.
Check a vitals reading, microphone capture, brightness change and companion launch. Cloud AI also
requires internet access, active Nolee AI entitlement and the stock Launcher's device-owned key.
Use the Nolee AI status page to check access; never print, export, copy into source, or log the key.

### Conversation behavior

Home's offline Voice Command can start the Cloud persona: say "open Nolee AI", "start AI" or
"turn on cloud AI". The small English model's grammar spells these as `no lee a i` and `a i`.
Opening is offline; answering through Cloud still requires internet and active entitlement.

When Cloud AI hands off to a device control, it completes the normal ball-to-Watch exit animation,
holds the Watch briefly, shows Home, then rolls through the destination cards and controls.
Touch or key input cancels the remaining automatic navigation. Conversation controls and Profile
updates stay on the persona page and continue listening without this handoff.

The `update profile` tool accepts a bounded patch of Name, Age, Occupation, City and About. It
saves to the same private app preferences the Profile page edits—not an arbitrary file path.
Clear owner-provided facts can be saved as they come up naturally; explicit "save this" is not
required. AI may ask for a missing name naturally, but should not interview the owner for the
other fields or repeat the question after a refusal. Updates preserve unspecified fields and
merge relevant About facts; clearing requires the owner's request. No guesses, quoted or
third-party facts, secrets or temporary requests belong in this profile. Saved values are sent
as context on the next question and persist across conversation sessions.

The most recent answer reveals one Unicode character every 30 ms. The transcript smoothly follows
the growing content, including answers taller than the viewport. Manual scrolling suspends
bottom-follow until a new turn; opening the transcript resumes it. Follow-up listening does not add
a shifting row beneath an answer. The heading does not grow with the conversation font size.

Nolee AI can run `show transcript`, `hide transcript`, `mute ai voice`, and `enable ai voice` tools.
Say "show the transcript", "hide the transcript", "mute your voice", or "turn your voice back on".
These affect this conversation or app-local spoken answers, not Android's media volume. A mute
command stops any remaining acknowledgement once the successful command response arrives.
Enabling audio applies to the next answer. Voice selection is a separate Nolee AI settings subpage.

Each request includes at most five prior completed question/answer pairs, plus the current question
and saved Profile facts. There is no conversation persistence or cross-session recall. Leaving the
persona, pausing the app, or restarting it destroys the Cloud client; a new client starts with no
turn history. Voice and spoken-answer preferences, and Profile fields, persist independently.
This describes conversational context, not server request-metadata retention.

### Voice-command coverage audit

Source-level inventory after adding conversation controls; opening a page is not the same as
operating every control or reading its live values. `Voice.kt` defines offline grammar,
`CloudCommands.kt` defines the Cloud allowlist, and `MainActivity.kt` executes the typed commands.

**Available through both Vosk and Cloud AI:** Home, Watch, Vitals and the three measurement pages;
System, Profile, Date & time, Wi-Fi, Bluetooth, Display, Sound and Launcher settings; launching
Camera, Files, Gallery, Phone and SMS; radio on/off; brightness percentage and adaptive mode;
media/ring/alarm/notification/call volume, mute all and restore sound; starting the Cloud AI persona; automatic time/zone;
the listed time zones; opening individual Profile fields for editing. Cloud understands natural
language but is restricted to these same actions. Vosk is limited to its English command grammar.

**Cloud-only:** general questions/conversation and usage-quota lookup; system/UI-effects volume;
show/hide transcript, enable/mute AI spoken answers and background Profile-field updates. These conversation-control phrases are
not added to Vosk's constrained offline grammar.

**Not directly voice-accessible through either system:**

- Nolee AI settings entry, Voice subpage/voice-name selection, explicit status
  refresh and validity lookup. Cloud can answer shared usage questions using its server usage tool,
  but cannot navigate to these new settings pages. Use the main menu and settings controls.
- Wi-Fi network selection, password entry and Connect/Cancel; Bluetooth scan, pairing and unpairing.
  Opening their settings and toggling their radios are supported.
- Setting an exact manual date/time with the Year/Month/Day/Hour/Minute controls or Set Clock/Now.
  Automatic clock and known time-zone commands are supported.
- Vosk cannot dictate/save Profile values; it opens the chosen field for keyboard editing.
  Cloud AI can now save Profile-field patches in the background without opening the editor.
- Selecting Steps, Temperature, Battery or Storage cards directly, reading those live values aloud,
  or reading a measurement result back to the model. Opening Vitals and measuring heart rate,
  oxygen or blood pressure are supported; measurement data is not included in Cloud context.
- Opening Power, confirming Restart/Shut down, leaving kiosk or changing primary/companion/root access.
  Power remains behind its physical-button entry and slide confirmations.
- Persona camera-view selection, transcript scrolling, back/forward navigation, or arbitrary tapping.
  Use swipe, lid, touch and navigation controls. Cloud can now show/hide the transcript.
- Operating inside companion apps: taking photos, picking files/photos, dialing a number, reading
  messages or sending SMS. Voice support launches the app only; there is no cross-app UI agent.

Cloud actions are deferred local dispatch, not live device-state feedback. The model cannot inspect
the display, read arbitrary settings/sensors, or confirm that a queued action actually succeeded.

### Ask AI provider probe

Historical internal provider probe (not required for building or using the app): measure the one-shot Qwen-Omni path from a desktop shell:

```powershell
$env:DASHSCOPE_API_KEY = "<Singapore Model Studio API key>"
python tools/qwen_omni_probe.py path\to\spoken-question.wav
```

The probe sends the WAV to `qwen3.5-omni-flash`, prints response-header, first-text,
first-audio and completion timings, and saves the returned speech beside the input. It uses the
Singapore DashScope endpoint by default. Set `DASHSCOPE_BASE_URL` to a workspace-dedicated Singapore
base URL when one is available; that is the preferred production endpoint.

### System, Vitals and permissions

- **System** is a taller rolling drum with live subtitles for Profile, Date &amp; time, Wi-Fi, Bluetooth, Display, Sound and Launcher. Its selected card is taller with larger text; other cards retain their size.
- **Page changes** (`Transitions.kt`) build a page up part by part: the page being left fades sideways in 220 ms, then the new one's title slides in from the left, its code from the right, and its rows follow 55 ms apart, each sliding in over 380 ms. Paced after Harness (620 ms panel, 270 ms rows), it is what makes a voice command read as the watch being operated. Parts claim their place in the order they first compose, so `Modifier.arrive` on a row is all a new page needs; the stagger stops counting past seven rows. Home, the watch face and the persona keep their own entrances, and a drum rises on the page clock.
- **Date &amp; time** (`DateTime.kt`) switches Android's automatic clock and time zone off and on, sets the zone from a short list, and sets the date and time by hand on stepper rows. Android keeps SET_TIME and SET_TIME_ZONE to privileged apps, so both go through root: `date MMDDhhmmYYYY.ss` (the only form the watch's toybox takes — it has no `date -s`) and `setprop persist.sys.timezone`, which the system picks up live. Automatic has to be off first or the network writes its answer straight back, and the process clears its own cached zone so the new one shows without a restart.
- **Profile** (`Profile.kt`) holds the owner's name, age, occupation, city and a short "about", in this app's private storage. The fields roll past as full-size cards on System's drum; opening one swaps the drum for a single field at the top of the page, clear of the keyboard. The lid and the D-pad roll and open them like any other drum. Home's greeting takes the name from here; with none set it greets without one ("Hello there"). Say "open profile" to get there by voice.
- **Controls act on the device** and were verified on the watch: the Wi-Fi and Bluetooth radios (public API, falling back to `su svc`), system brightness and adaptive mode (`Settings.System`, falling back to `su settings put`), and six audio streams. Wi-Fi scans and joins networks; Bluetooth lists paired devices, discovers and pairs. Levels use Harness's 18-segment control in mint.
- **Vitals** is its own main-menu entry. Heart rate, blood oxygen and blood pressure use the vendor measurement mode in 30 s windows (as Harness Vitals.kt), plus steps, SoC and board temperature, battery and storage. It runs only while the page is showing and the app is resumed; leaving turns measurement mode, listeners and the wakelock off.
- **Grants** after a fresh install come from `android/tools/setup_device.sh` (see *Setting up a watch*).
- **Keys** are handled in `dispatchKeyEvent`, before Compose focus navigation. Over adb, the first D-pad press after a touch is consumed by Android leaving touch mode; the watch's F-keys are unaffected.

### Fixes after first watch use

- **Home entrance** starts from black. The motion clock is keyed on each Home entry, so the first frame of Home already reads t = 0; a shared clock kept its finished value for that frame and flashed the whole page before the entrance.
- **Type size** ignores the system font scale inside the stage. Compose scales `sp` non-linearly at the watch's 1.5 font scale (large sizes far less than small), which had shrunk the 67 px clock to about 45 px. The Time face is now placed at the prototype's measured positions, centred in the ring.
- **Wi-Fi join** replaces any saved configuration for the SSID instead of reusing it (reuse silently retried the old password) and waits up to 20 s for the outcome: connected, wrong password, or failed.
- **Sound** has Mute all, which sends every stream to its minimum (the call stream cannot go below 1) and offers Restore levels until they are restored or a level is changed by hand.

### Vitals pages, watch face motion and type floor

- **Vitals** is a tall rolling drum (heart rate, blood oxygen, blood pressure, steps, temperature, battery, storage). The overview runs only the step counter and thermal polling. Heart rate, blood oxygen and blood pressure each open a sub-page that measures that one metric in back-to-back 30 s windows, with a 48-tick window ring, the reading and a contact/permission status. Leaving any Vitals page cancels its run: measurement mode, listeners and the wakelock go off (verified on the watch: `health_measure_status` 1 while open, 0 after Back).
- **Watch face** entrance (1.3 s): a scanline uncovers the readout, ticks boot clockwise with a mint flash, corner brackets lock on, digits decode from scrambled glyphs, and the date and zone rails slide in. Exit (0.48 s, drawn above the next page): digits scramble, ticks retract, and the face collapses to a line like a CRT switching off. The TIME / PERSONAL heading moved up to y 54, clear of the dial.
- **Type sizes** (design px): the status header is 14. Tags, the session line, page codes, section labels, row details, values and watch face labels are 13. Drum subtitles and counters are 12. Drum titles are 18.5 and row titles 15. The greeting types at 80 ms per character.
- **Idle watch face**: after 10 s the dial and readout grow 1.27× and settle into the centre of the screen as everything else fades; a touch brings them back. The full-screen clock adds a hairline, monochrome instrument layer with one mint accent: a fine graduation ring turning against a bracketed bezel, a single seconds index, and cardinal marks. The black space around it gets faint art: a star field, three tilted orbits each carrying a satellite, dashed crosshairs, and corner registration marks labelled CHRONO-01, the UTC offset, the day of the year and the date. Every ten seconds two slices of the digits jump sideways. Everything steps once a second with a 320 ms ease instead of animating continuously, to keep the watch cool.
- **Watch → AI persona**: while the idle watch is full-screen, long-press for 400 ms anywhere inside its dial to transform it into Signal Iris, a launcher-native conversational presence. The time compresses to a horizontal signal, turns upright, and unfolds into an asymmetric mint aperture while the clock graduations reorganize into activity bands. The 1.27× clock footprint, optical centre, hairline bezel, restrained palette and stepped instrument motion stay continuous across both states. A short side-button press returns to the watch. The iris aperture, cadence and intensity are intentionally ready to map to listening, thinking and speaking later.
- **Vitals measuring pages** sit on the plain grid, without the peace artwork. Each takes one 30 s reading and stops: the LEDs go off, the result stays on screen (COMPLETE or NO READING), and a tap measures again. Each has its own instrument, animated only while a window runs. Heart rate is an ECG monitor: a segment ring flaring on each beat, a pulse ping, and a sweeping PQRST trace. Blood oxygen is a vessel filling with waves and bubbles on a 100-tick scale, circled by a scanner arc. Blood pressure is a cuff gauge: the needle inflates and bleeds down, with systolic and diastolic arcs. Readings are 64 px (42 px for blood pressure) and status text is 15 px.

### Luminous ball persona (device preview, 2026-09-17)

The full-screen clock now starts morphing immediately on press. Holding for 650 ms
commits; an earlier release, cancelled touch, excessive drag or focus loss reverses
the same animation over 420 ms. There is no hold-progress ring. The native overlay
stays mounted across the Watch/Persona page change, so the morph does not restart.

`BallPersonaView.kt` ports the approved `preview/teal-transition.html` design to
Android Canvas: a translucent teal shell, blurred inner face light, a softly lit
rim and sparse rising particles. The approved defaults are 190% eye thickness,
125% eye spacing, 150% rim glow and 15% rim blur. Shell and face lighting are
cached alpha bitmaps; blur is computed once rather than per frame.

The clock outline contracts to an orb at 95% of the full-screen clock outline radius over 2.1 s. Clock digits keep
moving into rounded eyes throughout the overlapping fill transition, completing
at 2.2 s, while the full entrance settles over 3.65 s. The face gently breathes and
floats; the former solid sphere, bouncing, ground shadow and orbital decoration
are removed. The device refinement uses the watch face’s shared mint palette and
full-screen clock star-field/orbit background, a wider/taller and softer inner glow, and no top brand
or bottom preview caption. The emotion caption is hidden. The final orb is centered on the display, with its rim stroke matched to the full-screen clock’s teal seconds arc. Stage calibration, measured lens clipping, the 650 ms hold commit,
early-release reversal and lifecycle-controlled rendering remain in place.

That visual preview originally cycled emotions on tap. The current persona opens its transcript
on tap and runs the Cloud pipeline described at the top of this README. F9 or Back returns to
the full-screen clock. Offline Voice Command on Home retains its existing behavior.

### Ask AI voice commands (offline)

Tapping **Voice Command** on Home listens for one command, offline. It uses Harness's Vosk setup: `vosk-model-small-en-us-0.15` is copied from `Nolee_Robot/vosk-model-en-us-staging` into the APK at build time, and is unpacked on first use. Recognition is constrained to `VoiceGrammar.phrases`, so only commands the app can run are heard.

- **Listening** (`AskAi.kt`) happens on Home itself, with no overlay. The greeting backspaces and retypes into a question, which changes every 4.2 s until someone speaks: "Which app should I open?", "What should I change?", "Where would you like to go?", "Too bright? Too loud?", "Want a quick check-up?". "Designed for Vibe Coders" rolls to "Listening for command…". The app drum fades out and a mirrored waveform takes its place. Below it, a transcript types out what is heard, or shows an example that answers the question (`VoiceGrammarTest` checks that each example runs). A thin light runs round the lens edge, the session row reads MIC LIVE, and the bar becomes CANCEL. A tap, Back or the side button cancels.
- **Heard**: the greeting retypes to "Ok, on it!", the subtitle to "Executing command…", and the waveform rail lights mint beside the command ("OPENING VITALS"). As the command starts running, the edge light un-traces and fades, the drum returns, and the command plays out. Speech that is not a command listens again, up to twice: the greeting retypes to "Sorry, please say that again.", the subtitle reads "Listening again…", and a short buzz marks the microphone reopening. Silence ends the turn instead — nobody is talking to the watch — with "I didn't hear anything." A third unrecognised phrase gives "Sorry, I didn't catch that." Either way Home's own greeting comes back. Tapping Voice Command gives a firm double buzz.
- **Waveform**: `VoiceListener` reads the microphone itself instead of using Vosk's `SpeechService`, which never exposes the audio. Every 25 ms chunk is measured into `LevelMeter` for the bars, and 100 ms blocks go to the recognizer. The floor (-46 dBFS) sits just above a measured office room, so a quiet room shows only the resting breath.
- **Carrying it out** (`MainActivity.perform`, after Harness's dial and settings flows): the command runs through the screens a person would use, so it reads as the AI operating the watch. The drum rolls a card every 290 ms with a haptic tick. The chosen card flashes and opens, and so on down to the destination. There the control moves to its new value: the brightness bar and panel sweep, volume bars step, radios flip. Touching the watch cancels the rest.
- **Commands**: open any page ("open vitals", "measure blood oxygen", "wi fi settings", "show watch face", "open date and time", "open profile", "go home") or companion app ("open camera", "open messages"); "turn wi fi / bluetooth / adaptive brightness on or off"; "set brightness to forty five percent"; "set media / ring / alarm / notification / call volume to twenty percent"; "mute all"; "restore sound"; "turn automatic time / automatic time zone on or off"; "set time zone to tokyo" (any zone in `TIME_ZONES`, which the picker and the grammar share); "edit my name / age / occupation / city / about", which opens that field with the keyboard up. The small model has no "unmute", and it cannot take dictation, so a field's contents are still typed. Setting the clock itself stays on the steppers: spoken dates and times are more ways to be misheard than the grammar can carry.
- **Tests**: `VoiceGrammarTest` checks that every recognisable phrase maps to a command, plus spot checks.
- **Bench hook** (debug builds only): run a phrase as if heard, to check a flow without speaking: `adb shell am broadcast -a ai.nolee.brandedlauncher.DEBUG_VOICE --es text "turn wi fi off"`.
- **Testing under kiosk**: do not `am force-stop` the primary app. The Nolee Launcher treats it as a crash and shows its Recovery screen; navigate in-app (F5 for Home, taps found through `uiautomator dump`) instead.

The device persona adds 18% eye stroke weight, including matching particle targets.
After the entrance it makes bounded random glances with gentle tilts (up to 5°),
0.65–1.15 s eased movement and 1.3–3 s rests, sometimes returning to center.
The inner glow follows the eyes; the shell catches a subtler tilt. Gaze resets for
each entrance and its timer advances only with rendered frames.

The complete orb floats within 7 design pixels horizontally and 10 vertically of
center at a gently quicker pace. One broad blurred mint ground glow reacts to its
height, tightening/brightening as it lowers and widening/fading as it rises.
Two faint cached rim highlights travel in opposite directions with soft pulses.
Low ambient light and smooth star twinkles add movement over the shared clock
background. There is no dark shadow core; glow textures are cached at creation.

The enthusiastic motion pass runs ambient motion 1.85× faster after entrance,
with 0.30–0.58 s glances and 0.45–1.10 s rests. The whole orb adds a restrained
±3.5% size pulse and ±1.6% squash/stretch. The teal ground glow has a fixed screen
center and vertical spread; only its width and brightness follow the actual gap
to the scaled orb. Clock corner marks fade out with the outgoing clock artwork
and are not redrawn behind the persona.

The faster entrance completes in 1.9 s, with eyes filled at 1.25 s and body
contraction over 1.15 s. Shared timing constants keep the clock handoff and
background fade aligned. Hold-to-commit remains 650 ms. The fixed ground glow
sits 14 px lower and its width varies more strongly with the scaled orb-to-ground
gap. Three staggered shooting-star paths cross the background in 0.68 s with
72 px fading tails and bright soft heads, varying their paths on each pass.

The next motion refinement removes the shooting stars and replaces whole-head
scaling with a 1.35 s gentle bounce near center. Digit-to-eye dots are roughly
twice as large. Top particles use fixed dimensions outside the head transform.
Short two-beat nods or side-to-side shakes occur between randomized pauses;
the existing glances, rim highlights, fixed ground glow and ambient stars remain.

Thinking now morphs the eyes and inner mask into a mint tumbling ribbon loop,
using projected layered strokes and depth shading. It is attached to the existing
PersonaEmotion.Thinking visual state (debug tap cycle); cloud request integration
remains separate. The orb follows a ground-contact bounce with squash/stretch
anchored at impact. The fixed shadow responds to actual scaled clearance.
Rim reflections follow head orientation relative to an upper-left light source.
The 650 ms hold commit gives one strong double haptic; cancelled holds do not.


Persona refinement (video reference, 2026-09-17): Thinking uses one soft filled teal sheet with a traveling twist/pinch across its horizontal axis, replacing the outlined loop strands. Reference frames extracted at tmp/ribbon-reference. Ground bounces now choose a 1.95–2.65 second period and 48–76 design-pixel height per impact; gaze/gestures and ambient movement are slower. Each impact releases 36 larger, independently positioned particles, fading within 0.72 seconds during ascent. No particles continuously emit in flight. Ground shadow remains anchored and reacts to actual clearance.

Thinking ribbon refinement: expanded the filled sheet to nearly the full sphere diameter, with faster variable twisting, vertical sweeps, changing fullness and gentle roll. Ribbon is centered independently of gaze and clipped within the shell. Impact particles rise 145–190 design pixels and are suppressed throughout Thinking and its exit fade.

Persona now exposes only Regular and Thinking (debug tap toggles them). Ribbon surface opacity is reduced to 52% of its former value. On landing, Regular opens a single local Vosk listening turn with the same EdgeLight and silence timers as Ask AI: 10 seconds without speech, or 1 second of silence after recognized speech. This turn uses open vocabulary and does not execute local commands or send speech to cloud AI. Completion/failure, entering Thinking, leaving the page, or pausing the app stops the listening glow/capture.

Thinking settles the orb at screen center before the ribbon appears, with bounce and squash suspended. Ribbon silhouette now receives a reusable low-resolution three-pass blur, approximating the face mask softness. EdgeLight follows persona transition progress immediately from touch-down and retraces with the same 420 ms release reversal; completed entry hands off to the existing listening timeout.

Persona polish: reduced ribbon blur to two radius-2 passes and raised surface opacity to 68% for clearer folds. Persona edge light blends deep teal, mint and cyan (including its entry trace). Listening adds Harness VoiceListeningBackdrop's vertical bold LISTENING treatment at the left, behind the orb and above the starfield, fading with the existing listening session.

Listening typography now uses the app's bundled Spline Sans Mono, retaining the vertical backdrop placement. Ribbon returns to direct mesh rendering without silhouette blur; cached satin shading, a narrow flowing highlight and orientation-based lighting detail the translucent surface. Centered Thinking and listening behavior are unchanged.

Back from Persona now reverses the morph over 950 ms before completing navigation: preserve the current body position/scale, recenter, and reform current watch digits. The watch resumes settled without replaying its entrance. Listening lettering is white with a slower fade; ribbon twist runs at a constant fast 7.6 radians per animation second.

Thinking now maintains a rapid 28 rad/s tumble on an independent phase clock (no entry-only acceleration caused by morphing absolute elapsed time). White LISTENING waits for the first actual ground contact, then reveals L, I, S, etc. bottom-to-top at 100 ms spacing. Edge light returns to the white/silver treatment.

Thinking gains two anchored mint rim highlights that pulse in opposition and a faint outward halo ripple. Regular gains the watch-style two-band horizontal glitch (9/-6 design-pixel offsets decaying over 260 ms, first after 8 seconds then every 10 seconds). Glitches are suspended during Thinking and navigation transitions.

Listening reveal is faster (45 ms between letters, 70 ms per-letter fade), still triggered by first impact. When the arrival listening turn completes with no recognized speech, a 5-second grace period leads into the existing reverse watch transition; speech, Thinking, leaving or pausing cancels that return. Thinking adds a centered ±1.8% scale pulse.

Experimental persona CRT lens: non-interactive foreground overlay across the entire AI page, including the edge light, with fine low-contrast scanlines, cached animated grain, vignette, slow scan beam and occasional thin interference bands. Lifecycle-aware 15 Hz drawing; follows the existing entrance/reversal progress and leaves the watch page clear.

Removed the traveling scan beam; kept static scanlines, animated grain, vignette and fine interference. The periodic two-strip glitch now wraps the entire AI composition, including background, ball, text and edge light; removed the separate ball-only glitch. It still runs only in regular state and stops during transitions.

Full-screen glitch strengthened: all eight bands covering the entire frame now shift, with shared horizontal/vertical jitter and slight scale disturbance. Six abrupt signal changes decay over 300 ms; cadence remains about ten seconds. Replaces the prior two localized strips.

Thinking entry now starts the eyes/mask-to-ribbon morph immediately alongside recentering, sharing the same easing speed. Removed the near-settled position gate that caused a pause before the face changed.

Experimental camera orbit: a smooth ten-second center → upper-left viewpoint → center loop. The starfield shifts/tilts with stronger parallax, while face/ribbon perspective, rim reflection and ground-shadow projection respond at nearer depths. Sphere stays nearly centered and circular; listening text and screen chrome stay fixed. Motion fades out during the reverse watch transition and pauses with lifecycle.

Camera orbit is now gesture-controlled: swipe right across the sphere to ease into and hold the upper-left viewpoint; swipe left across it to return front-on. Removed automatic looping, slightly increased parallax for legibility, and reserve the leftmost 12% for the existing system back gesture. A new persona entry resets the camera.

Swipe camera made more apparent: expanded parallax, stronger background yaw/pitch/roll, larger sphere projection shift, and increased facial foreshortening. Face/ribbon are clipped inside the shell at the stronger angle; centered view remains unchanged.

AI background brightness increased: orbit lines 2.6×, stars about 1.9× with brighter satellite halos and subtle crosshair lift. Brightness blends with the persona transition; standard watch face keeps its existing brightness.

Rendering optimization: cache static CRT scanlines/vignette and mint tint filters; update grain at its actual 132 ms cadence (33 ms around interference); compute ribbon trigonometry per column with cached row lighting coefficients; skip fully transparent draws. Existing sphere pacing, mesh resolution, effects and motion speeds retained. USB watch short 15-second Thinking comparison: process CPU ticks 697 → 497 (~29% lower); rendered frames 751 → 528 by removing redundant lens updates; median/95th render time 9/12 ms in both; jank 0/751 → 3/528, 99th 13 → 15 ms. These are short samples, not controlled thermal measurements. Raw captures in tmp/persona-perf-before.json and tmp/persona-perf-final.json.

Camera now alternates front/upper-left views after 8 seconds idle, with a 1.5-second automatic ease. Center swipes still choose either view immediately with 750 ms easing and restart the idle timer, including repeated swipes in the same direction. Timer is lifecycle-aware, sleeps between moves, and stops during exit. Shared lens/tint/invisible-draw optimizations apply to Regular as well as Thinking; the recorded 29% CPU comparison was specifically Thinking.

Transcript and voice visuals: tapping the orb opens a scrollable transcript overlay instead of cycling emotion. Cloud transcription and streamed replies appear in the current session; tapping the overlay or system Back dismisses the panel first. Thinking replaces the vertical LISTENING label with THINKING. Speaking starts with actual speaker playback and adds eased, slightly offset rhythmic eye-height/width animation; that animation is not phoneme lip-sync. Debug builds can preview states using DEBUG_VOICE with persona_state=regular/thinking/speaking and optional text, without affecting tap behavior. The debug receiver requires the shell-level DUMP permission.

Transcript overlay is now full-screen with a 60% black scrim, no card/border, and 240/200 ms fade in/out. Tap anywhere on the transcript to close; scrolling still scrolls. Hold the ball 650 ms to switch to Regular and restart Vosk listening (including from Thinking/Speaking); movement cancels the hold, and a completed hold does not also open the transcript. Same 10-second no-speech/1-second post-speech silence expiry and 5-second silent-return grace apply.

Camera views now cycle Front → Upper-left → Right → Top tilt with a 15-second idle interval and 1.8-second automatic easing. Yaw/pitch are independent so right and top views have matching background parallax, face/ribbon foreshortening and rim reflections. Swipes retain the existing upper-left/front override and restart the timer. Cloud pipeline files and activity integration are unchanged.
