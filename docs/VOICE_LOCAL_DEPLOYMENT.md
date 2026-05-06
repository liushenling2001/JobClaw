# JobClaw Local Voice Deployment

JobClaw voice interaction is implemented as an auxiliary local sidecar. The
agent runtime, tools, subtasks, memory, artifacts, harness, and completion logic
remain inside JobClaw. The sidecar only handles speech input and speech output.

The local runtime and model files live under `voice-sidecar-local/`, which is
ignored by Git. This document and helper scripts are versioned.

## Runtime Layout

```text
JobClaw/
  docs/VOICE_LOCAL_DEPLOYMENT.md
  docs/voice-sidecar-start.ps1
  docs/voice-sidecar-prefetch-models.ps1
  voice-sidecar-local/        # ignored by Git
    python/
    adapter/
    logs/
    models/
      asr/
        faster-whisper-small/
      tts/
        Kokoro-82M-v1.1-zh/
        Qwen3-TTS-12Hz-0.6B-CustomVoice/
        Qwen3-TTS-12Hz-1.7B-CustomVoice/
```

## Current Local Baseline

The current tested local runtime uses:

```text
Python: voice-sidecar-local/python/python.exe
GPU: NVIDIA GeForce RTX 3060 Laptop GPU
PyTorch: 2.8.0+cu128
Torchaudio: 2.8.0+cu128
ASR: faster-whisper-small
Default TTS: Kokoro-82M-v1.1-zh
Fallback TTS: Qwen3-TTS-12Hz-0.6B-CustomVoice
```

Kokoro is the preferred default because the Qwen3 TTS models are too slow for
interactive local playback on the current 6 GB VRAM machine. JobClaw still keeps
Qwen3 TTS support available for quality comparison or future hardware.

## How JobClaw Uses Voice

Input flow:

```text
Browser microphone
 -> POST /api/voice/transcribe
 -> local sidecar ASR
 -> recognized text
 -> normal JobClaw chat stream
```

Output flow:

```text
Assistant text stream
 -> frontend sentence/chunk segmenter
 -> POST /api/voice/tts per segment
 -> WAV audio queue
 -> browser plays segments in order
```

This is intentionally not a separate speech-to-speech agent. Voice is an input
and output channel around the existing agent chain. Tool logs, harness events,
and subagent internals are not spoken by default.

## Backend Endpoints

```text
GET  /api/voice/status
GET  /api/voice/voices
POST /api/voice/transcribe   audio/wav -> transcript JSON
POST /api/voice/tts          { text, voice } -> audio/wav
```

The Java backend starts a local Python sidecar on demand at
`127.0.0.1:18792`. The generated adapter script is written to:

```text
voice-sidecar-local/adapter/voice_sidecar_server.py
```

## TTS Engine Selection

Default engine resolution:

```text
1. -Djobclaw.voice.ttsEngine=kokoro|qwen
2. JOBCLAW_TTS_ENGINE=kokoro|qwen
3. kokoro if voice-sidecar-local/models/tts/Kokoro-82M-v1.1-zh/kokoro-v1_1-zh.pth exists
4. qwen fallback
```

Default model path resolution:

```text
1. -Djobclaw.voice.ttsModel=...
2. JOBCLAW_TTS_MODEL=...
3. Kokoro model directory when engine=kokoro
4. Qwen3 0.6B model directory when engine=qwen and present
5. Qwen3 1.7B model directory fallback
```

Engine-specific overrides:

```text
JOBCLAW_KOKORO_MODEL_DIR=voice-sidecar-local/models/tts/Kokoro-82M-v1.1-zh
JOBCLAW_QWEN_TTS_MODEL=voice-sidecar-local/models/tts/Qwen3-TTS-12Hz-0.6B-CustomVoice
```

For Qwen dtype:

```text
1. -Djobclaw.voice.ttsDtype=bf16|fp16
2. JOBCLAW_TTS_DTYPE=bf16|fp16
3. bfloat16 for Qwen3 0.6B
4. float16 fallback
```

Model residency:

```text
1. -Djobclaw.voice.keepModelsLoaded=true|false
2. JOBCLAW_VOICE_KEEP_MODELS_LOADED=true|false
3. default true for Kokoro
4. default true for Qwen3 0.6B
5. default false for larger Qwen fallback models
```

## Voices

Kokoro voices are discovered from:

```text
voice-sidecar-local/models/tts/Kokoro-82M-v1.1-zh/voices/*.pt
```

The default Kokoro voice is:

```text
zf_001
```

Common Kokoro voices:

```text
zf_001, zf_002, zf_003, zf_004, zf_005, zm_010
```

Qwen voices:

```text
Vivian, Serena, Uncle_Fu, Dylan, Eric, Ryan, Aiden, Ono_Anna, Sohee
```

If the frontend has an old selected voice in local storage, it is reset to the
backend default when `/api/voice/voices` returns the active voice list.

## Startup And Test

Start JobClaw normally. The sidecar starts only when ASR or TTS is first used.

To force Kokoro:

```powershell
$env:JOBCLAW_TTS_ENGINE='kokoro'
java -jar target/jobclaw-1.0.0.jar
```

To force Qwen:

```powershell
$env:JOBCLAW_TTS_ENGINE='qwen'
java -jar target/jobclaw-1.0.0.jar
```

Check status after JobClaw starts:

```powershell
Invoke-RestMethod http://127.0.0.1:18791/api/voice/status
```

When the sidecar is running, its direct health endpoint should report the active
engine:

```powershell
Invoke-RestMethod http://127.0.0.1:18792/health
```

Expected Kokoro response includes:

```text
ttsEngine: kokoro
```

## Local Verification

Kokoro has been locally verified with:

```text
Model load: about 3 seconds
Warm segment synthesis: about 0.15-0.20 seconds for short Chinese segments
Output sample rate: 24 kHz
Output format: WAV
```

The frontend speaks incrementally: as assistant text streams in, it segments
sentences, synthesizes each segment, and plays the generated WAV blobs in order.
This keeps perceived latency lower than waiting for the full answer.

## Operational Notes

- Browser microphone access works on `localhost`, `127.0.0.1`, or HTTPS.
- First TTS call is slower because the Kokoro model is loaded lazily.
- If voice output fails, the text chat stream continues.
- Stop any old `voice_sidecar_server.py` process before switching engines.
- The sidecar refuses silent reuse of a running sidecar with a different TTS
  engine/model so tests do not accidentally use stale Qwen state.
- Kokoro Chinese TTS uses an English G2P callable for mixed Chinese/English
  text, so normal English words such as `JobClaw`, `OpenAI API`, and `Python`
  are preserved instead of being replaced by unknown tokens.
- Frontend speech cleanup strips markdown and normalizes common units before TTS.
