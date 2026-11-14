# loqui-android

Loqui is a background-first voice input app: tap the floating bubble, speak, and the
transcript lands in whatever field is focused. Recognition runs on-device by default;
cloud providers are strictly opt-in and use **the user's own API key**.

This repository is **Phase 3: trigger + insertion UX**. The floating bubble shows
idle/listening/processing/result states, a tap starts or stops the recording service,
and the transcript is pasted into the focused field through the accessibility service —
or lands on the clipboard with a toast when no field is focused. All of it with **zero
API keys** and no network.

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

## Architecture (Phase 3)

| Concern | Where | Notes |
| --- | --- | --- |
| Microphone owner | `capture/VoiceInputService` | `microphone` foreground service; the only recorder |
| Capture | `audio/AudioCaptureStream` + `audio/AudioRecordPcmSource` | `AudioRecord` 16 kHz mono PCM-16, 20 ms frames, released as soon as collection stops |
| Ring buffer | `audio/AudioRingBuffer` | Bounded circular buffer between the blocking read and the consumer; drops oldest audio and counts the loss |
| Endpointing | `audio/vad/SpeechEndpointer` + `audio/vad/EnergyVad` | Onset (60 ms) → speech → hangover (800 ms) → utterance complete; detector is swappable for Silero |
| Floating trigger | `trigger/BubbleService` + `BubbleController` | `specialUse` overlay service: draggable disc, tap toggles capture, never records audio or touches the network |
| Bubble states | `trigger/BubbleAppearance` | Pure mapping `CaptureState` → accent, short label, spinner, pulse; the only `View` code lives in `BubbleController` |
| Recognition | `asr/AsrCoordinator`, `asr/AsrProviderFactory` | `FakeAsrProvider` on-device until sherpa-onnx lands; `asr/cloud/CloudAsrProvider` for opt-in cloud, gated on the user's key |
| Insertion | `insertion/DefaultTextInserter` | Always copies to clipboard first, then attempts an accessibility paste; clipboard is the guaranteed fallback |
| Accessibility | `insertion/LoquiAccessibilityService` | Optional, user-enabled; `ACTION_PASTE` first, `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` when paste is refused |
| Insertion text math | `insertion/InsertionText` | Pure caret/selection planning, unit-tested without a device |
| Feedback | `capture/CaptureFeedback` + `capture/MessageGate` | One toast per outcome even though the bubble and the mic service both observe it |
| Permissions | `ui/onboarding/OnboardingScreen` + `core/Permissions` | Mic + notifications + overlay requested in-app, accessibility deep-links to Settings, opens on first launch |
| Settings | `settings/DataStoreSettingsRepository` | DataStore preferences |
| Secrets | `settings/KeystoreSecretStore` | AES-256-GCM key in Android Keystore, ciphertext in SharedPreferences, never logged |

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

Until steps 3 and 5 are recorded here, the Phase 3 boxes in `04-phases.md` stay unticked.

## Deliberate Phase 3 gaps

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
