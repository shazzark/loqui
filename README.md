# loqui-android

Loqui is a background-first voice input app: tap the floating bubble, speak, and the
transcript lands in whatever field is focused. Recognition runs on-device by default;
cloud providers are strictly opt-in and use **the user's own API key**.

This repository is **Phase 2: audio + ASR core**. It records real microphone audio at
16 kHz mono PCM-16, endpoints it with VAD, and runs the utterance through the
`AsrProvider` selected by settings — all with **zero API keys** and no network.

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

## Architecture (Phase 2)

| Concern | Where | Notes |
| --- | --- | --- |
| Microphone owner | `capture/VoiceInputService` | `microphone` foreground service; the only recorder |
| Capture | `audio/AudioCaptureStream` + `audio/AudioRecordPcmSource` | `AudioRecord` 16 kHz mono PCM-16, 20 ms frames, released as soon as collection stops |
| Ring buffer | `audio/AudioRingBuffer` | Bounded circular buffer between the blocking read and the consumer; drops oldest audio and counts the loss |
| Endpointing | `audio/vad/SpeechEndpointer` + `audio/vad/EnergyVad` | Onset (60 ms) → speech → hangover (800 ms) → utterance complete; detector is swappable for Silero |
| Floating trigger | `trigger/BubbleService` + `BubbleController` | `specialUse` overlay service; draws the bubble and sends start/cancel commands, never records audio or touches the network |
| Recognition | `asr/AsrCoordinator`, `asr/AsrProviderFactory` | `FakeAsrProvider` on-device until sherpa-onnx lands; `asr/cloud/CloudAsrProvider` for opt-in cloud, gated on the user's key |
| Insertion | `insertion/DefaultTextInserter` | Always copies to clipboard first, then attempts an accessibility paste; clipboard is the guaranteed fallback |
| Accessibility | `insertion/LoquiAccessibilityService` | Optional, user-enabled; used only to find the focused editable field and paste |
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

## Deliberate Phase 2 gaps

- On-device recognition is still `FakeAsrProvider`; the sherpa-onnx engine lands with
  the model download work. Everything around it (capture, endpointing, flow shape,
  settings, failure states) is real.
- The cloud provider is complete except its transport: `MockCloudAsrTransport` returns
  a canned transcript, so the Deepgram/AssemblyAI client can be dropped in behind
  `CloudAsrTransport` without touching the provider.
- VAD is an energy detector. It proves the pipeline deterministically; Silero ONNX
  replaces it behind `VoiceActivityDetector` when models ship.
- No `android.permission.INTERNET` in the manifest yet: nothing makes a request, and the
  permission should land with the cloud client that actually uses it.
