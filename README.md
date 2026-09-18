# Nolee Branded Launcher

A native Android example for Nolee DevKit Ultra: animated Home/Watch navigation, device controls,
vitals, offline Quick Command and a streaming Nolee AI ball persona. Fork it or use it as a full
app reference. `android/` contains the device app.
The native app currently uses portrait orientation. It does not replace the stock Launcher's
device management, activation or security boundaries.

This is an optional example app that you can adapt or replace with your own.

The interface and offline Quick Command currently use English. When helping an owner customize
this example, ask whether they want it translated into their preferred language; do not infer
language from their country. Translate UI text separately from the Vosk speech model/grammar,
and test fitting text on the small display. Landscape support is also an optional development
task. Neither translation nor landscape support is already included.

The Android package is `ai.nolee.brandedlauncher`.

The app icon uses the mint N monogram on a dark green adaptive background, including round
launcher masks. Its artwork is in `android/app/src/main/res/drawable-nodpi/nolee_icon_n.png`.

## Build

Use JDK 17, Android SDK 35/platform tools and Python 3.10+. Set `ANDROID_HOME` or
`android/local.properties` to your SDK. From the repository root:

```text
python android/tools/setup_model.py
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

On Windows use `gradlew.bat`. Model setup downloads the official small English Vosk model over
HTTPS and verifies its pinned SHA-256 before extraction. It is ignored by Git and bundled in
the APK so Quick Command works offline. An existing extracted model can be selected with
`-PvoskModelDir=/absolute/model/path` or `VOSK_MODEL_DIR`. No private sibling repository,
Nolee key or model-provider API key is required to build.

Output: `android/app/build/outputs/apk/debug/app-debug.apk`. Debug builds include a shell-permission
protected test receiver; release builds do not register it. `assembleRelease` produces an unsigned
APK: use your own signing configuration to distribute a fork, without committing private keys.

## Owner-approved setup and kiosk companions

Installing alone is not sufficient. Ask the owner before enabling root, location services or
changing kiosk configuration. Use an explicit ADB serial and `adb -s SERIAL install -r APK`
to preserve app data.

Before enabling kiosk, **ask which companion apps the owner wants**. Offer Nolee Phone
(`ai.nolee.phone`), SMS (`ai.nolee.sms`), Camera (`ai.nolee.camera`), Gallery (`ai.nolee.gallery`)
and Files (`ai.nolee.files`) unless they have their own set. Install/check the chosen apps,
request companion approvals, then enter kiosk. Unapproved companions are blocked. Preserve
existing approvals unless the owner asks to remove them. For alternatives, change `AppEntry`
package mappings and manifest `<queries>` too: an allowlist alone does not change launch buttons.

With AI agent access enabled and kiosk exited, configure this app's required access:

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

Root is a Nolee Launcher grant, **not** `pm grant ROOT`. It gives this trusted app broad privileged
access, not access restricted to one credential. Review forks before approving it in stock
Launcher → System → Access → Root access. Never enable a device-wide root bypass.

Using the same provider, approve each chosen companion with `set_companion --arg PACKAGE
--extra enabled:b:true`; set `set_primary_app --arg ai.nolee.brandedlauncher`, then
`request_kiosk --arg ai.nolee.brandedlauncher`. Require `ok=true` in every response: a successful
shell exit does not establish acceptance.

After the owner approves the complete setup, run from Bash (Git Bash on Windows):

```bash
android/tools/setup_device.sh SERIAL
# Optional replacement set; an empty string requests no new companions:
NOLEE_COMPANIONS="com.example.camera com.example.phone" android/tools/setup_device.sh SERIAL
```

The script checks companions, exits kiosk, installs, grants access, adds companions, sets primary
and requests kiosk. It does not remove existing companions. Reapply grants after uninstall/reset.
Microphone enables both voice modes; sensors/secure settings enable vitals; location enables
Android 9 Wi-Fi/Bluetooth discovery; WRITE_SETTINGS enables brightness. Companions need their own
permissions. Do not grant this app unrelated camera/contacts/phone/storage permissions.
Check grants, a vitals reading, microphone, brightness and companion handoff before delivery.
Do not force-stop a kiosk primary app to navigate: that invokes recovery.

## Start talking

1. Activate Nolee AI in the stock Nolee Launcher before opening this app's AI conversation.
2. From Home, open **Watch**, let the full-screen face appear, then hold the face to enter
   Nolee AI. Alternatively, choose **Home → Nolee AI → Start Nolee AI**. It begins listening.
3. To ask another question or interrupt an answer, hold the ball until the listening feedback
   appears. Releasing too early cancels the hold.
4. Long-touch the camera lid to open the transcript. Its **Ask** button starts listening;
   **Interrupt** stops the current answer and listens again. The waveform means recording is
   active. With spoken answers off, the transcript opens automatically.
5. For **offline Vosk Quick Command**, tap **Quick Command** at the bottom of Home and say a
   supported command, such as “open camera”, “open Nolee AI”, “open Nolee AI settings”, or
   “leave kiosk”. This is a separate command recognizer, not the cloud AI conversation or an
   always-listening wake word. It works without Nolee AI activation or an internet connection.

Nolee AI needs internet access and uses the device's shared AI allowance. Its settings let you
change spoken answers, voice and web search. The side button returns from the AI screen to Watch.

## Nolee AI integration for your own app

Activate Nolee AI in stock Launcher first. Hold the full-screen Watch face or choose
Home → Nolee AI → Start Nolee AI. Offline Quick Command can also route there via Watch.

[CloudAi.kt](android/app/src/main/java/ai/nolee/brandedlauncher/CloudAi.kt) reads
`/system/nolee/credentials/nolee-key` through owner-approved root **at each request**, and uses it
in memory as a Bearer header to the fixed HTTPS Nolee Cloud endpoint. Redirects are disabled.
Do not export it, print/log it, embed it in an APK or copy it into source, preferences, `.env`
or secrets files. The coding agent does not need to extract or see the key: the app reads it.
Stock Launcher owns activation/rotation/revocation; this app never writes the credential.
A replacement is picked up on the next request without reinstalling.

For a fork, follow these boundaries:

1. Obtain owner approval and handle missing root/key with a useful setup message.
2. Send `/ask` with `mode: "general"` and your `app_prompt` (at most 4,000 characters).
   `APP_PROMPT` describes this app, its persona, controls and memory. Adapt it to your own app.
3. Send a question or 16 kHz mono PCM WAV, then consume streamed transcript/text/audio/errors.
   Voice preferences and tool capabilities belong to your app.
4. Let the backend enforce activation, expiry, revocation and shared usage on every request.
   Recording is not authorization. Listening does not need a `/status` preflight; settings
   fetch status/usage on entry or explicit refresh.
5. Cancel capture, HTTP, playback and queued actions on interruption/lifecycle exit.
   Do not blindly retry a failed stream: allowance may already have been consumed.

At most five completed prior Q&A pairs accompany the current question. Interrupted partial
answers are excluded. Leaving/pausing the persona destroys its client; fresh sessions have no
previous-session conversation. Profile and voice preferences persist separately in private app
preferences. Questions/audio, selected recent turns and nonempty Profile fields go to Nolee Cloud;
no screenshots, camera images or live UI state are sent. This describes app context, not server
request-metadata retention. Both apps share the device's allowance. Surface offline/expired/
revoked/quota errors rather than trying to bypass them.

## Local tools and Profile

Web search is allowed by default. The AI can select search and device commands together in one
turn, including spoken-answer controls and Profile updates. Nolee AI settings → Web search only
controls permission to search; device tools remain enabled either way. A device-only request
does not consume search allowance. Preference changes apply to the next question.

Profile includes an independent **Country** field. The local `ai.nolee.brandedlauncher.provisioning`
ContentProvider supports initializing Name and Country. It requires `android.permission.DUMP`
and accepts only ADB shell/root UIDs. `set_recipient` takes base64 UTF-8 JSON `{ "name": "Alex", "country": "HK" }`
in `--arg`; `get_recipient` returns the same fields as base64 in `recipient`. Both return `ok=true`
only on success. Writes validate the bounded fields, commit synchronously, preserve other Profile
fields, and refresh an already open Profile/Home. There is no unprotected Intent-extra setup path.
Nonempty Country is included with the other profile facts in AI requests.

[CloudCommands.kt](android/app/src/main/java/ai/nolee/brandedlauncher/CloudCommands.kt) declares
bounded local actions. The server validates up to three `device.actions`; the app validates again
and executes after a successful response/playback. Errors/cancellation discard queued actions.
Most setting changes use deferred local dispatch: the model cannot verify their success from
the queued action alone. The opt-in media-volume event described below runs before the reply. Never execute arbitrary model-generated shell commands or treat
model output as additional permission. Sensitive/destructive tools in forks need local confirmation.

`app_prompt` explains when to use capabilities; it does not implement them. Custom apps can
advertise their own command names in `device_commands` and handle the returned actions locally.
The command payload supports `command`, the defined volume/brightness `percent` argument, and
`profile` for `update profile`; it is not an arbitrary argument schema. Tools with other arguments
or custom result formats need gateway support, not only a prompt change.

`get_device_status` is a separate read-only tool with a result round trip. When the model
requests it, the app reads the watch's current date/time/timezone, battery/charging,
Wi-Fi/Bluetooth state, brightness, volume streams, storage, kiosk and AI preferences.
The app returns the readings before the answer is generated; they are not attached to every
question. Unavailable readings are null. Wi-Fi association does not establish internet access,
and the watch clock is not independently verified. No location, network names, contacts,
messages, health readings or hardware identifiers are included.

The app advertises `device_status_tool: true`, handles the SSE `device.tool_call` event,
and posts its bounded result to `/device-tool-result` with the same device credential and
request/call IDs. The original stream continues, so this uses one question allowance.
Cancellation stops the callback along with the main request. See
[DeviceStatusTool.kt](android/app/src/main/java/ai/nolee/brandedlauncher/DeviceStatusTool.kt).

Device actions animate Watch → Home → destination. Profile/transcript/audio controls stay in
conversation. Profile tools save clearly volunteered owner facts to bounded Name/Age/Occupation/
City/Country/About fields, preserving unspecified fields. Empty strings clear only when requested.
Prompt rules prohibit inferred/third-party facts and secrets, but are not a hard semantic security
boundary. Owners can inspect/edit the Profile page; saved facts are context on the next question.

## Controls and source map

- Short side button: Home/Watch or Persona → Watch. Long press/System → Shutdown: Power page,
  where Restart/Shut down still require slide confirmation.
- Hold the ball to interrupt/listen with haptics; early release/movement cancels the hold.
- Long-touch camera lid to toggle transcript; Back closes it. Opening fades
  over 600 ms and closing over 500 ms, retaining content through exit.
- Swipe persona left/right for views, up for top, down for front.
- Backend progress uses THINKING, SEARCHING, COMPLETE and REPLYING labels, all aligned
  with LISTENING. COMPLETE marks backend tool processing and may be brief; pending device
  actions still follow the reply.
- Speaker and status labels match the transcript body size. Transcript text streams and smoothly follows; existing answers appear immediately when reopened. Manual scrolling pauses follow until a new turn
  or reopening. AI can show/hide transcript and mute/enable its own replies.
- With spoken answers off, entering AI automatically opens the transcript. Its fixed Ask
  button starts another microphone window without closing the transcript; during an answer,
  Interrupt cancels the current turn and starts listening. While recording, the pill shows an
  animated waveform and is disabled.
- Offline voice: “open Nolee AI settings” opens AI settings; “open Nolee AI” starts a conversation.
  Vosk uses the dictionary spelling “no lee a i” for the brand pronunciation.
- Offline voice and Nolee AI accept “exit kiosk” / “leave kiosk” to return to stock Launcher.
  Successful exits show no toast. The outer outline fades first, then the ball shrinks and
  fades while the background fades at full size. Failed exits restore the screen and show an error.
- Nolee AI can adjust its speaking volume with “set your volume to 40 percent” or “speak quieter”.
  The `set ai volume` tool changes the shared media stream and stays in the conversation.
  Other media shares that level; ring, alarm, notification and call volume are unaffected.
  Media-volume changes apply before the acknowledgement is displayed or played, so that reply
  uses the requested level. A later response failure does not undo an applied volume change.
  Other device actions still wait for successful reply/playback completion.
  Spoken answers on/off remains a separate control.
- Nolee AI settings show status/shared usage, Web search, Spoken answers and a separate Voice subpage.

`MainActivity.kt`: navigation/lifecycle; `CloudAi.kt`: requests/capture/playback;
`CloudCommands.kt`/`ProfileUpdates.kt`: validation; `PersonaTranscript.kt`: chat;
`Voice.kt`: independent offline grammar. Tests: `android/app/src/test/`.

See [third-party notices](THIRD_PARTY_NOTICES.md).
Project code is [MIT licensed](LICENSE). Nolee trademarks are not licensed for endorsement.
