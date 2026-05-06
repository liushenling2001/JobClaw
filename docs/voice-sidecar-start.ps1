param(
    [int]$Port = 18792,
    [string]$TtsEngine = "kokoro"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$voiceRoot = Join-Path $repoRoot "voice-sidecar-local"
$pythonRoot = Join-Path $voiceRoot "python"
$modelsRoot = Join-Path $voiceRoot "models"
$adapterScript = Join-Path $voiceRoot "adapter\voice_sidecar_server.py"
$asrModel = Join-Path $modelsRoot "asr\faster-whisper-small"
$kokoroModel = Join-Path $modelsRoot "tts\Kokoro-82M-v1.1-zh"
$qwenModel = Join-Path $modelsRoot "tts\Qwen3-TTS-12Hz-0.6B-CustomVoice"

$python = Join-Path $pythonRoot "python.exe"
if (-not (Test-Path $python)) {
    throw "python.exe not found: $python"
}
if (-not (Test-Path $adapterScript)) {
    throw "Adapter script not found: $adapterScript. Start JobClaw once or use /api/voice/status so the Java backend can write it."
}
if (-not (Test-Path (Join-Path $asrModel "model.bin"))) {
    throw "ASR model not found: $asrModel"
}

$env:PYTHONIOENCODING = "utf-8"
$env:JOBCLAW_VOICE_PORT = [string]$Port
$env:JOBCLAW_VOICE_ROOT = $voiceRoot
$env:JOBCLAW_ASR_MODEL = $asrModel
$env:JOBCLAW_TTS_ENGINE = $TtsEngine
$env:JOBCLAW_KOKORO_MODEL_DIR = $kokoroModel
$env:JOBCLAW_QWEN_TTS_MODEL = $qwenModel
$env:JOBCLAW_TTS_MODEL = if ($TtsEngine -eq "kokoro") { $kokoroModel } else { $qwenModel }
$env:JOBCLAW_VOICE_KEEP_MODELS_LOADED = "true"
$env:HF_HOME = Join-Path $modelsRoot "hf-cache"
$env:HUGGINGFACE_HUB_CACHE = Join-Path $modelsRoot "hf-cache\hub"
$env:TRANSFORMERS_CACHE = Join-Path $modelsRoot "hf-cache\transformers"
$env:HF_DATASETS_CACHE = Join-Path $modelsRoot "hf-cache\datasets"
$env:TORCH_HOME = Join-Path $modelsRoot "torch"
$env:XDG_CACHE_HOME = Join-Path $modelsRoot "xdg"
$env:HF_HUB_OFFLINE = "1"
$env:TRANSFORMERS_OFFLINE = "1"

if ($TtsEngine -eq "kokoro") {
    if (-not (Test-Path (Join-Path $kokoroModel "kokoro-v1_1-zh.pth"))) {
        throw "Kokoro model not found: $kokoroModel"
    }
} elseif ($TtsEngine -eq "qwen") {
    if (-not (Test-Path (Join-Path $qwenModel "model.safetensors"))) {
        throw "Qwen TTS model not found: $qwenModel"
    }
} else {
    throw "Unsupported TtsEngine: $TtsEngine. Use kokoro or qwen."
}

& $python $adapterScript
