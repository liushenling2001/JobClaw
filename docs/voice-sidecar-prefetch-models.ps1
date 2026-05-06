$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$voiceRoot = Join-Path $repoRoot "voice-sidecar-local"
$pythonRoot = Join-Path $voiceRoot "python"
$modelsRoot = Join-Path $voiceRoot "models"

New-Item -ItemType Directory -Force $modelsRoot | Out-Null
New-Item -ItemType Directory -Force (Join-Path $modelsRoot "hf-cache") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $modelsRoot "torch") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $modelsRoot "asr") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $modelsRoot "tts") | Out-Null

$env:HF_HOME = Join-Path $modelsRoot "hf-cache"
$env:HUGGINGFACE_HUB_CACHE = Join-Path $modelsRoot "hf-cache\hub"
$env:TRANSFORMERS_CACHE = Join-Path $modelsRoot "hf-cache\transformers"
$env:HF_DATASETS_CACHE = Join-Path $modelsRoot "hf-cache\datasets"
$env:TORCH_HOME = Join-Path $modelsRoot "torch"
$env:XDG_CACHE_HOME = Join-Path $modelsRoot "xdg"

$python = Join-Path $pythonRoot "python.exe"
if (-not (Test-Path $python)) {
    throw "python.exe not found: $python"
}

$prefetchScript = Join-Path $voiceRoot "prefetch_models.py"
$prefetchCode = @'
from huggingface_hub import snapshot_download
from pathlib import Path

root = Path(r"__MODELS_ROOT__")

models = [
    ("STT", "Systran/faster-whisper-small", root / "asr" / "faster-whisper-small"),
    ("TTS", "hexgrad/Kokoro-82M-v1.1-zh", root / "tts" / "Kokoro-82M-v1.1-zh"),
    ("TTS", "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice", root / "tts" / "Qwen3-TTS-12Hz-0.6B-CustomVoice"),
]

for kind, repo_id, target in models:
    print(f"Downloading {kind} model: {repo_id}")
    target.mkdir(parents=True, exist_ok=True)
    path = snapshot_download(repo_id=repo_id, local_dir=str(target))
    print(f"{kind} model cached at: {path}")
'@
$prefetchCode = $prefetchCode.Replace("__MODELS_ROOT__", $modelsRoot.Replace("\", "\\"))
Set-Content -LiteralPath $prefetchScript -Value $prefetchCode -Encoding UTF8
& $python $prefetchScript
