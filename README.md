# loqui-android

Loqui is a background-first voice input app: tap the floating bubble, speak, and the
transcript lands in whatever field is focused. Recognition runs on-device by default;
cloud providers are strictly opt-in and use **the user's own API key**.

This repository is **Phase 5: hardening and distribution**. The floating bubble shows
idle/listening/processing/result states, a tap starts or stops the recording service, and
the transcript is pasted into the focused field through the accessibility service — or
lands on the clipboard with a toast when no field is focused. An optional clean-up pass
fixes punctuation and capitalisation, capped at **one second**; when it is off, slow,
failing or has no key, the raw transcript is inserted. Phase 5 adds the distribution
paperwork: a microphone service that survives Android 14/15 restrictions and a revoked
grant, a battery-optimisation exemption, the in-app privacy policy, the Play accessibility
disclosure, and the storage/licence scaffolding for on-device model downloads. All of it
with **zero API keys** and no network.

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

## Architecture (Phases 3-5)

| Concern | Where | Notes |
| --- | --- | --- |
| Microphone owner | `capture/VoiceInputService` | `microphone` foreground service; the only recorder |
| Capture | `audio/AudioCaptureStream` + `audio/AudioRecordPcmSource` | `AudioRecord` 16 kHz mono PCM-16, 20 ms frames, released as soon as collection stops |
| Ring buffer | `audio/AudioRingBuffer` | Bounded circular buffer between the blocking read and the consumer; drops oldest audio and counts the loss |
| Endpointing | `audio/vad/SpeechEndpointer` + `audio/vad/EnergyVad` | Onset (60 ms) → speech → hangover (800 ms) → utterance complete; detector is swappable for Silero |
| Floating trigger | `trigger/BubbleService` + `BubbleController` | `specialUse` overlay service: draggable disc, tap toggles capture, never records audio or touches the network |
| Bubble states | `trigger/BubbleAppearance` | Pure mapping `CaptureState` → accent, short label, spinner, pulse; the only `View` code lives in `BubbleController` |
| Recognition | `asr/AsrCoordinator`, `asr/AsrProviderFactory` | `FakeAsrProvider` on-device until sherpa-onnx lands; `asr/cloud/CloudAsrProvider` for opt-in cloud, gated on the user's key |
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
| Model catalogue | `models/SpeechModels` | sherpa-onnx entry with its license; nothing bundled, no URL wired yet |
| Model storage | `models/ModelStorage` + `models/ModelStorageProbe` | Pure archive-plus-unpacked-space decision, `formatBytes`, `StatFs` probe behind an interface |

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
    unpacked copy, and the UI ships the button disabled until the engine exists.

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
- `SpeechEndpointerTest` — silence and single-frame clicks open nothing, onset + hangover emits exactly the utterance, endpoint stops pulling audio, ceiling cuts long speech
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
- `FakeAsrProviderTest` — audio in, one partial + one final out; an empty stream fails
  instead of inventing text

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
- `SpeechModelsTest` — catalogue ids are unique, the default model is listed, and every
  entry has a plausible size and a license that allows commercial use
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
6. **Model scaffold.** *On-device model* shows the sherpa-onnx Zipformer entry, "About
   120 MB · Apache 2.0 license", the free-space line and a disabled *Download model*
   button. Filling the disk is not necessary: check that a device with < 184 MB free shows
   the shortfall line instead of the "enough" line.

Until these are recorded here, the Phase 5 boxes in `04-phases.md` stay unticked.

## Deliberate Phase 3-5 gaps

- On-device recognition is still `FakeAsrProvider`; the sherpa-onnx engine lands with
  the model download work. The bubble, permissions, insertion and clipboard paths it
  feeds are real.
- The cloud provider is complete except its transport: `MockCloudAsrTransport` returns
  a canned transcript, so the Deepgram/AssemblyAI client can be dropped in behind
  `CloudAsrTransport` without touching the provider.
- VAD is an energy detector. It proves the pipeline deterministically; Silero ONNX
  replaces it behind `VoiceActivityDetector` when models ship.
- No `android.permission.INTERNET` in the manifest yet: nothing makes a request, and the
  permission should land with the cloud client that actually uses it.
- Bubble position is not persisted across service restarts, and there is no Compose UI
  test harness yet (no Robolectric), so the screen logic is covered through the pure
  state mappers instead.
- The on-device corrector is deterministic punctuation, not a language model: it proves
  the bounded clean-up path with no model download, and a real model slots in behind
  `LlmCorrector` without touching the coordinator or the insertion path.
- Cloud clean-up is complete except its transport: `MockCloudLlmTransport` performs no
  network I/O, so an OpenAI/DeepSeek client drops in behind `LlmTransport` without
  touching the coordinator, the prompt or the 1 s budget.
- The model section is a scaffold: `SpeechModels` records the license and the size but no
  URL, no checksum and no downloader, and the *Download model* button is disabled. The
  sherpa-onnx engine and the download service land together, behind the existing
  `ModelStorageProbe`/`SpeechModel` types.
- The privacy policy is in-app only. Play requires a hosted URL as well, so the listing
  copy has to be generated from `PrivacyPolicy.sections` before release; `PrivacyPolicy.VERSION`
  is the trigger for regenerating it.
- The Play Console accessibility declaration and its justification text are a submission
  step, not code: the wording lives in `disclosure_body`/`disclosure_bullets` and the
  service config, but the form is filled in by hand.
- Battery profiling numbers (mA/doze behaviour on real hardware) and OEM QA on Samsung,
  Xiaomi and Pixel are unmeasured; `BatteryOptimizations` only makes the exemption
  reachable.
- No `android.permission.INTERNET` in the manifest yet: nothing makes a request, and the
  permission should land with the cloud client that actually uses it.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a Play-policy-sensitive permission. The
  in-app request is deliberate for a background voice service, and the battery-list
  fallback plus the "optional" copy is what keeps it defensible; revisit if review objects.
