# Custom Launcher Cloud AI baseline

Accepted development baseline, 2026-09-17.

- Package: `ai.nolee.customlauncher`
- Version: `0.3.0` / version code `3`
- Artifact: `nolee-custom-launcher-0.3.0-cloud-ai-baseline.apk`
- Build: debug-signed development APK; this is the exact installed artifact, not a rebuilt copy.
- SHA-256: `294aae64e0aa166761033083fd2db331156438c4258e5c721a30f449c5012323`
- Verified installed on development unit `95579408611132` with the same SHA-256.

Includes the ball persona design, Cloud audio capture/transcription, streaming replies and playback,
follow-up listening, in-session context, direct device Nolee-key access and app-defined prompt.
Home Vosk commands remain separate. App tests: 13 passing. Debug and release builds compiled.

The installed baseline was built before a subsequent comment-only update to Profile.kt; no executable
source changed after the final build. The README records current integration behavior.

Cloud dependency: the separately deployed Nolee Cloud gateway supports `app_prompt` for general mode
and distinct per-minute rate-limit errors. It is not bundled with this APK. An activated device key,
microphone permission and owner-approved app root access are required. There is no embedded API key.

Build prerequisites include the Android SDK and the external Vosk model at
`Software/Nolee_Robot/vosk-model-en-us-staging`, as described by the Gradle configuration.
The existing APK can be restored without rebuilding. For the same installed signing identity:

```text
adb -s DEVICE_SERIAL install -r nolee-custom-launcher-0.3.0-cloud-ai-baseline.apk
```

This baseline is for development/refinement; no website release or Launcher OTA is implied.
