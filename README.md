# loqui-android

Loqui is a background-first voice input app: tap the floating bubble, speak, and the
transcript lands in whatever field is focused. Recognition runs on-device by default;
cloud providers are strictly opt-in and use **the user's own API key**.

This repository is **Phase 6: real on-device ASR**. The floating bubble shows
idle/listening/processing/result states, a tap starts or stops the recording service, and
the transcript is pasted into the focused field through the accessibility service — or
lands on the clipboard with a toast when no field is focused. An optional clean-up pass
fixes punctuation and capitalisation, capped at **one second**; when it is off, slow,
failing or has no key, the raw transcript is inserted. Phase 6 replaces the on-device
stand-in with **sherpa-onnx** streaming Zipformer, downloaded from the official release
into app-private storage, checksum-verified per file and unpacked only after the archive
matches. The bubble, permissions, privacy paperwork and distribution work from Phases 3-5
stay intact. All of it with **zero API keys**; the only network request is the model
download the user explicitly starts.

## Build

Requirements:

- JDK 17+
- Android SDK with platform 37 (`local.properties` → `sdk.dir`, or `ANDROID_HOME`)
- Gradle wrapper is committed; no separate Gradle install needed

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
```

No keys, no `.env`, no `google-services.json`: if a build ever asks for a credential,
that is a bug.

## Architecture (Phases 3-6)

| Concern | Where | Notes |
| --- | --- | --- |
| Microphone owner | `capture/VoiceInputService` | `microphone` foreground service; the only recorder |
| Capture | `audio/AudioCaptureStream` + `audio/AudioRecordPcmSource` | `AudioRecord` 16 kHz mono PCM-16, 20 ms frames, released as soon as collection stops |
| Ring buffer | `audio/AudioRingBuffer` | Bounded circular buffer between the blocking read and the consumer; drops oldest audio and counts the loss |
| Endpointing | `audio/vad/SpeechEndpointer` + `audio/vad/EnergyVad` | Onset (60 ms above 0.008 RMS, ≈ -42 dBFS) → speech → hangover (800 ms) → utterance complete; a session also closes after 5 s without speech and is cut at 15 s, so a mis-tuned gate can never hold the bubble |
| Floating trigger | `trigger/BubbleService` + `BubbleController` | `specialUse` overlay service: draggable disc, tap toggles capture, never records audio or touches the network |
| Bubble states | `trigger/BubbleAppearance` | Pure mapping `CaptureState` → accent, short label, spinner, pulse; the only `View` code lives in `BubbleController` |
| Recognition | `asr/AsrCoordinator`, `asr/AsrProviderFactory` | `asr/SherpaOnnxAsrProvider` on-device (sherpa-onnx streaming Zipformer, int8); `asr/cloud/CloudAsrProvider` for opt-in cloud, gated on the user's key |
| Clean-up (optional) | `llm/LlmCoordinator`, `llm/LlmProviderFactory` | Off by default; `OnDeviceLlmCorrector` (deterministic punctuation) or `llm/cloud/CloudLlmCorrector`, hard-capped at 1 s — any skip inserts the raw transcript |
| Prompt | `llm/LlmPrompt` | One reviewable instruction: fix punctuation, never add/remove words, reply with the text only |
| Insertion | `insertion/DefaultTextInserter` | Always copies to clipboard first, then attempts an accessibility paste; clipboard is the guaranteed fallback |
| Accessibility | `insertion/LoquiAccessibilityService` | Optional, user-enabled; `ACTION_PASTE` first, `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` when paste is refused |
| Insertion text math | `insertion/InsertionText` | Pure caret/selection planning, unit-tested without a device |
| Feedback | `capture/CaptureFeedback` + `capture/MessageGate` | One toast per outcome even though the bubble and the mic service both observe it |
| Permissions | `ui/onboarding/OnboardingScreen` + `core/Permissions` | Mic + notifications + overlay requested in-app, accessibility deep-links to Settings, opens on first launch |
| Settings | `settings/DataStoreSettingsRepository` | DataStore preferences |
| Secrets | `settings/KeystoreSecretStore` | AES-256-GCM key in Android Keystore, ciphertext in SharedPreferences, never logged |
| FGS hardening | `hardening/CaptureStartFailure` | Pure classifier for `ForegroundServiceStartNotAllowedException` / `SecurityException`; `VoiceInputService.start()` and `goForeground()` return the reason instead of throwing at the caller |
| Mic revocation | `capture/VoiceInputService` | 500 ms grant watchdog inside the session plus a `SecurityException` catch: a revoked microphone ends the session with a message, never a stuck bubble |
| Battery | `core/BatteryOptimizations` | `isIgnoringBatteryOptimizations` + `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with a battery-list fallback for OEMs without the dialog |
| Privacy policy | `privacy/PrivacyPolicy` + `ui/privacy/PrivacyPolicyScreen` | Versioned, reviewable section list rendered in-app; reachable from home and onboarding |
| Accessibility disclosure | `privacy/DisclosurePolicy` + `ui/privacy/AccessibilityDisclosureDialog` | Play prominent disclosure before the settings deep-link, accepted once and persisted |
| Model catalogue | `models/SpeechModels` | sherpa-onnx entry with its license, archive URL, archive SHA-256 and per-file checksums/sizes; nothing bundled |
| Model download | `models/KtorModelDownloader` | Ktor/OkHttp HTTPS GET with `Range` resume, 512 KiB progress steps, archive SHA-256 check, streaming `.tar.bz2` unpack that hashes each file while writing |
| Model install state | `models/FileSpeechModelRepository` | App-scoped state machine `NotInstalled → Downloading → Verifying → Installing → Installed`; staging directory renamed into place only after every file verifies |
| Model storage | `models/AppModelStorage` + `models/ModelStorageProbe` | App-private `filesDir/models`; pure archive-plus-unpacked-space decision, `formatBytes`, `StatFs` probe behind an interface |
| On-device engine | `asr/StreamingAsrEngine` + `asr/SherpaOnnxStreamingEngine` | One recogniser for the app's lifetime (rebuilt only when paths/sample rate change), a fresh `OnlineStream` per dictation; endpointing stays Loqui's job |

Design rules that later phases must not break:

1. **Background-first.** Capture must keep working when the app is not in front, so the
   recording service is the `microphone` FGS while the bubble stays a lightweight overlay.
2. **On-device by default.** Nothing leaves the device unless the user explicitly selects
   a cloud mode *and* stores their own key.
3. **No operator-paid fallback.** If `AsrMode.CLOUD` is selected without a user key, the
   coordinator emits `AsrSessionEvent.NeedsKey` and stops. It never silently degrades to
   a metered endpoint or to a vendor key shipped in the APK.
4. **Insertion is best effort, never silent data loss.** The transcript is on the
   clipboard before any paste attempt, so a failed paste is a user action, not a lost
   dictation.
5. **The overlay never owns the microphone.** `BubbleService` draws and forwards taps;
   only `VoiceInputService` records. A tap while listening cancels — the bubble can
   never leave a session running unattended.
6. **One toast per outcome.** The bubble and the mic service both observe the final
   state; `MessageGate` collapses the duplicate so the user is told once.
7. **Correction never gates insertion.** `LlmCoordinator` runs the corrector inside a
   one-second `withTimeoutOrNull`, validates the answer and falls back to the raw
   transcript on disable, missing key, failure, timeout or an empty answer. A model can
   improve the text; it can never delay, block or delete a dictation.
8. **Refused starts are explained, never swallowed.** `VoiceInputService.start()` returns
   a `CaptureStartFailure` (Android 12+ background start, Android 14 microphone type,
   missing grant); the bubble and the in-app button render it as an error state with a fix.
   A tap must never be a silent no-op.
9. **A revoked grant ends the session, it never hangs it.** The microphone watchdog inside
   the session and the `SecurityException` catch turn a permission taken away mid-dictation
   into the "microphone revoked" state, so the bubble cannot sit on "Listening" over a dead
   microphone.
10. **Every permission has a visible, optional path.** Battery exemption, accessibility and
    notifications each show their current state plus the exact place to change it; nothing
    is required for the foreground app to dictate, and the clipboard fallback outlives all
    of them.
11. **No download without a license and a space check.** `SpeechModels` records the license
    with the model, `ModelStorageReport` refuses a download that would not still fit its
    unpacked copy, and the card shows every install stage instead of a blind spinner.
12. **Nothing unverified reaches the recogniser.** The archive is checked against the
    catalogue SHA-256, then each unpacked file is hashed and size-checked while it is
    written into a staging directory. Only a fully verified directory is renamed into
    place, so `SpeechModelRepository.paths()` never points at a partial model.

## Tests

`app/src/test/.../asr/AsrCoordinatorTest.kt` covers the mode/key matrix:

- on-device mode transcribes with no key at all
- cloud mode without a key reports `NeedsKey` and produces no transcript
- cloud mode with a user key transcribes through the cloud provider
- `availability()` reports `MissingKey` exactly when a cloud mode lacks a key
- a wedged provider hits the session hard limit instead of holding the microphone

`app/src/test/.../audio/` covers the capture pipeline:

- `AudioRingBufferTest` — wrap-around ordering, overflow drops the oldest bytes, frames never split
- `AudioCaptureStreamTest` — one 20 ms frame per read, partial reads reassembled, mic released on end *and* on cancellation
- `SpeechEndpointerTest` — silence and single-frame clicks open nothing, onset + hangover emits exactly the utterance, endpoint stops pulling audio, a session with no speech closes at the 5 s deadline, and the ceiling cuts long speech
- `AudioPipelineTest` — scripted microphone → ring buffer → VAD → provider → final transcript
- `CloudAsrProviderTest` — `MissingKey` with no request, key carries the utterance, rejections are not retried, timeouts are retried then surface

`app/src/test/.../trigger/` and `.../insertion/` cover the Phase 3 UX rules:

- `BubbleAppearanceTest` — idle/listening/processing are visually distinct, outcomes and
  errors get their own accent + label, and a tap only stops a session that is running
- `InsertionTextTest` — caret insertion, selection replacement, unknown caret appends,
  out-of-range selection clamps, inverted selection collapses
- `DefaultTextInserterTest` — clipboard is written *before* the paste attempt, and every
  accessibility outcome maps to `Pasted` or `CopiedOnly`
- `MessageGateTest` — the same outcome is toasted once; different messages always show
- `FakeAsrProviderTest` — audio in, one partial + one final out; an empty stream reports
  no speech instead of inventing text

## Device check (Phase 3 exit criterion)

On a real device with the debug build (no key, no model):

1. Fresh install → the setup screen opens: grant microphone + notifications, "Display
   over other apps", then enable **Loqui text insertion** in Accessibility settings.
2. "Start using Loqui" starts the bubble. Drag it anywhere; it stays over other apps.
3. Open Chrome, focus the address bar or a text area, tap the bubble.
   - bubble turns red and pulses, label "Listening…"
   - speak for ~2 s, then stop; after the 800 ms hangover the bubble shows "Working…"
   - the transcript ("This is a Loqui test transcript.") appears in the field and the
     bubble flashes green "Inserted"
4. Tap the bubble again while it is listening → the session cancels back to idle.
5. Focus nothing editable (e.g. the launcher) and dictate → toast
   "No text field focused — transcript copied to clipboard", bubble shows "Copied".

**Recorded on a physical device (2026-11-14):** steps 1-5 pass — the bubble works over
other apps, accessibility insertion pastes into the focused field, and the clipboard
fallback reports with a single toast. The boxes are ticked in `04-phases.md`.

## Tests: clean-up (Phase 4)

`app/src/test/.../llm/` covers the correction pass:

- `PunctuationCorrectionTest` — initial capital, terminal period, `,`/`;`/`:` become `.`,
  existing `.!?…` are left alone, numbers and blank input stay untouched
- `LlmCoordinatorTest` — switched off never builds a corrector, on-device fixes
  punctuation with no key, cloud without a key keeps the raw text, cloud with a key uses
  the model answer, a slow corrector is cancelled at exactly the 1 s budget, failures and
  empty answers fall back to the raw text
- `CloudLlmCorrectorTest` — `MissingKey` with no request made, the prompt carries the
  transcript and never the key, rejections are not retried, the corrector enforces its
  own timeout
- `LlmProviderFactoryTest` — on-device corrector reused, cloud rebuilt per session so the
  key is read at call time, `MissingKey` until a key is stored

## Device check (Phase 4 exit criterion)

On a real device with the debug build (no key, no model):

1. Home screen → *Clean-up (optional)*: the toggle is off, and a dictation lands exactly
   as recognised ("This is a Loqui test transcript.").
2. Switch clean-up on with *On-device*: dictate "this is a loqui test transcript" and
   confirm the field receives "This is a Loqui test transcript." — initial capital and a
   period, nothing else changed.
3. Select *Cloud (your key)* without storing a key: dictate again and confirm the raw
   transcript is inserted, the "Add your key to use cloud clean-up" card is visible, and
   nothing crashes.
4. Time step 2 against step 1 and confirm insertion is no more than ~1 s later with
   clean-up on.

**Recorded on a physical device (2026-11-14):** steps 1-3 pass, and step 2 shows the
default on-device clean-up capitalising and punctuating the transcript with no perceptible
delay (well inside the 1 s budget). The boxes are ticked in `04-phases.md`.

## Tests: hardening and distribution (Phase 5)

`app/src/test/.../hardening/`, `.../models/` and `.../privacy/` cover the Phase 5 rules
without a device:

- `CaptureStartFailureTest` — a `SecurityException` without the grant blames the microphone,
  with the grant blames the foreground promotion; `IllegalStateException` (the Android 12+
  `ForegroundServiceStartNotAllowedException`) is a blocked background start; everything
  else is unknown; every failure has a user-facing message
- `ModelStorageTest` — exact-fit is room, one byte short is not, an unreadable free space
  reports the whole download as missing, the default margin covers the unpacked copy, and
  sizes format as `B`/`KB`/`MB`/`GB`
- `SpeechModelsTest` — catalogue ids are unique, the default model is listed and
  downloadable, its checksums are 64-character hex, `requiredBytes` covers archive plus
  unpacked files, and every entry has a plausible size and a commercial-use license
- `PrivacyPolicyTest` — every required topic (audio, on-device default, cloud opt-in,
  clean-up, logging, keys, accessibility) is present, no section repeats, the policy is
  versioned
- `DisclosurePolicyTest` — the accessibility disclosure is required until accepted,
  settings open only after consent, and re-rendering never counts as consent

## Device check (Phase 5 exit criterion)

On a real device, with the bubble running:

1. **Battery.** Home screen → *Background reliability* shows "Battery optimisation may stop
   the bubble"; tap *Allow background running*, accept the system dialog, return to Loqui →
   the card now reads "Battery optimisation is off for Loqui" (resume re-reads the state).
2. **Revoked microphone.** Dictate once, then (without touching Loqui) switch off the
   microphone permission in system settings, reopen Loqui and tap *Dictate now* → the error
   state says the microphone permission is missing; no crash, bubble returns to idle.
3. **Background start.** With the app in the background (home screen, no bubble running),
   trigger a capture through the notification path → Android's refusal surfaces as the
   "Android blocked background recording" message rather than a silent no-op.
4. **Privacy policy.** Open *Privacy policy* from home and from the setup screen: 10
   sections, version line, back returns to the previous screen.
5. **Accessibility disclosure.** Clear app data, open setup, tap *Open settings* on the
   accessibility row → the disclosure dialog appears first; *I understand* opens the system
   accessibility page, *Not now* leaves the clipboard path intact. After accepting once,
   the dialog no longer appears.
6. **Model card.** *On-device model* shows the sherpa-onnx Zipformer entry, "About 122 MB
   download · Apache 2.0 license · 42 MB on disk", the free-space line and an enabled
   *Download model* button. A device with less free space than the archive plus its
   unpacked copy shows the shortfall line and the download fails before any request.

## Tests: model download and on-device ASR (Phase 6)

`app/src/test/.../models/` and `.../asr/` cover the install pipeline and the recogniser
with fakes, so no device or native library is needed:

- `SpeechModelRepositoryTest` — a completed install verifies, unpacks and moves into place;
  a partial archive resumes; a network failure keeps the part file; a checksum mismatch and
  a cancel delete it; progress never exceeds the archive size; an installed model is not
  downloaded again; too little free space fails before any request
- `ModelDownloaderHashTest` — `sha256Hex` matches the known digest of the fixture bytes and
  `toHex` pads values below `0x10`
- `SherpaOnnxAsrProviderTest` — a missing model reports `MODEL_UNAVAILABLE` without opening
  the engine; frames become de-duplicated partials plus one trimmed final; zero usable audio
  (empty flow or torn frames) reports `NO_SPEECH` instead of inventing text; an engine that
  throws surfaces `PROVIDER_ERROR` and still releases the session
- `Pcm16Test` — little-endian PCM-16 converts to floats in `[-1, 1]`, and an odd trailing
  byte is dropped rather than read past the end of the frame
- `AsrProviderFactoryTest` — on-device returns the injected app-scoped provider, cloud is
  rebuilt per session

## Device check (Phase 6 exit criterion)

On a device with the debug build:

1. **Install.** Home → *On-device model* → *Download model*: the card shows a percentage
   plus byte counts. Leave Home and return — the transfer keeps running, because it lives
   in the application scope rather than the ViewModel.
2. **Cancel and retry.** Cancel mid-download → the card returns to *Not installed* and the
   partial archive is removed. Start again, then enable airplane mode mid-transfer → the
   network failure is explained; disable airplane mode and tap *Retry* → progress resumes
   from the bytes already on disk instead of restarting at 0.
3. **Verify and unpack.** Completion passes through *Verifying* and *Unpacking* to
   *Installed* with the on-disk size. With `adb shell run-as <debug package>`, confirm
   `files/models/<id>/` holds `tokens.txt`, `encoder.onnx`, `decoder.onnx` and `joiner.onnx`
   and that no `.part` or `.staging` entry is left behind.
4. **Dictate offline.** Airplane mode on; dictate "this is a loqui test transcript" → the
   recognised sentence is inserted, on-device mode asks for no key, and no request is made.
   Switching to cloud stays gated on the user's key.

**Recorded on a device (2026-11-16, Galaxy A13, `armeabi-v7a`):** step 1 and the install
pass — the archive downloads and verifies, all four files unpack, and the native
`libsherpa-onnx-jni.so` loads. Step 4 did not: the bubble stayed on "Listening" and no
transcript arrived. Cause: the energy gate (`0.015`) sat above what that microphone delivers
through `VOICE_RECOGNITION`, so onset never confirmed, no frame reached the recogniser and
the only exit was the 60 s ASR hard limit. Fixed by lowering the gate to `0.008`, closing a
session after 5 s without speech, cutting an utterance at 15 s, and adding the diagnostics
below. Step 4 is pending a re-test on the same device.

### Dictation diagnostics (debug builds)

`Timber` logs one line per second of capture plus the endpoint and recogniser trace:

```
adb logcat -s AudioCaptureStream SpeechEndpointer SherpaOnnxAsrProvider VoiceInputService
```

- `Captured N frames (N ms), last frame RMS 0.0xx` — microphone level, never audio. A level
  under `0.008` is why the gate would not confirm onset.
- `Utterance closed by SILENCE_HANGOVER|MAX_DURATION|NO_SPEECH_TIMEOUT after N frames` —
  which ceiling ended the session, and how much audio reached the recogniser.
- `Recogniser ready in N ms`, `Audio ended: N frames, N ms, N partials`,
  `Final transcript: N chars` — the on-device path. `Audio ended: 0 frames` means nothing
  was sent, which is reported to the user as "No speech detected".
- `No endpoint after 30000 ms; the microphone stopped delivering audio` — the service
  watchdog fired because no frames arrived at all.

## Deliberate Phase 3-6 gaps

- The cloud provider is complete except its transport: `MockCloudAsrTransport` returns
  a canned transcript, so the Deepgram/AssemblyAI client can be dropped in behind
  `CloudAsrTransport` without touching the provider.
- VAD is an energy detector. It proves the pipeline deterministically; Silero ONNX
  replaces it behind `VoiceActivityDetector` when models ship.
- Bubble position is not persisted across service restarts, and there is no Compose UI
  test harness yet (no Robolectric), so the screen logic is covered through the pure
  state mappers instead.
- The on-device corrector is deterministic punctuation, not a language model: it proves
  the bounded clean-up path with no model download, and a real model slots in behind
  `LlmCorrector` without touching the coordinator or the insertion path.
- Cloud clean-up is complete except its transport: `MockCloudLlmTransport` performs no
  network I/O, so an OpenAI/DeepSeek client drops in behind `LlmTransport` without
  touching the coordinator, the prompt or the 1 s budget.
- The model catalogue has one English streaming Zipformer. A second language is a new
  `SpeechModel` entry with measured archive and per-file checksums — no code change.
- The vendored sherpa-onnx AAR is committed under `app/libs` (49 MB) and the APK ships
  `arm64-v8a`, `armeabi-v7a` and `x86_64`; `x86` is the only ABI left out.
- A reinstall does not rebuild an already-loaded recogniser: `SherpaOnnxStreamingEngine`
  caches by model directory, so replacing the model takes effect after a process restart.
- The free-space line on the model card is refreshed when Home resumes, not live during or
  after an install.
- The privacy policy is in-app only. Play requires a hosted URL as well, so the listing
  copy has to be generated from `PrivacyPolicy.sections` before release; `PrivacyPolicy.VERSION`
  is the trigger for regenerating it.
- The Play Console accessibility declaration and its justification text are a submission
  step, not code: the wording lives in `disclosure_body`/`disclosure_bullets` and the
  service config, but the form is filled in by hand.
- Battery profiling numbers (mA/doze behaviour on real hardware) and OEM QA on Samsung,
  Xiaomi and Pixel are unmeasured; `BatteryOptimizations` only makes the exemption
  reachable.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a Play-policy-sensitive permission. The
  in-app request is deliberate for a background voice service, and the battery-list
  fallback plus the "optional" copy is what keeps it defensible; revisit if review objects.
