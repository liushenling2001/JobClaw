package io.jobclaw.voice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jobclaw.config.Config;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class VoiceSidecarService {

    private static final Logger logger = LoggerFactory.getLogger(VoiceSidecarService.class);
    private static final int DEFAULT_PORT = 18792;
    private static final List<String> QWEN_VOICES = List.of(
            "Vivian", "Serena", "Uncle_Fu", "Dylan", "Eric", "Ryan", "Aiden", "Ono_Anna", "Sohee"
    );
    private static final List<String> FALLBACK_KOKORO_VOICES = List.of(
            "zf_001", "zf_002", "zf_003", "zf_004", "zf_005", "zm_010"
    );

    private final Config config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Path voiceRoot;
    private final int port;
    private Process process;

    public VoiceSidecarService(Config config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.voiceRoot = resolveVoiceRoot();
        this.port = resolvePort();
    }

    public synchronized VoiceStatus status() {
        boolean runtimeReady = Files.exists(pythonPath())
                && Files.exists(asrModelPath().resolve("model.bin"))
                && ttsRuntimeReady();
        boolean running = isRunning();
        return new VoiceStatus(
                runtimeReady,
                running,
                running ? "running" : runtimeReady ? "ready" : "missing-runtime-or-models",
                endpoint(),
                voiceRoot.toString(),
                asrModelPath().toString(),
                ttsModelPath().toString(),
                defaultVoices(),
                defaultVoice()
        );
    }

    public List<String> voices() {
        if (!isRunning()) {
            return defaultVoices();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint() + "/voices"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
                Object voices = body.get("voices");
                if (voices instanceof List<?> list && !list.isEmpty()) {
                    return list.stream().map(String::valueOf).toList();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to query voice list from sidecar: {}", e.getMessage());
        }
        return defaultVoices();
    }

    public TranscriptionResult transcribe(byte[] wavBytes) throws IOException, InterruptedException {
        ensureStarted();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint() + "/asr"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(wavBytes))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        ensureSuccess(response.statusCode(), response.body());
        Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
        return new TranscriptionResult(
                String.valueOf(body.getOrDefault("text", "")).trim(),
                String.valueOf(body.getOrDefault("language", "zh")),
                numberValue(body.get("languageProbability"))
        );
    }

    public byte[] synthesize(String text, String voice) throws IOException, InterruptedException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        ensureStarted();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text.trim());
        payload.put("voice", normalizeVoice(voice));
        payload.put("language", "Chinese");
        payload.put("instruct", "用自然清晰的普通话朗读。");
        byte[] json = objectMapper.writeValueAsBytes(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint() + "/tts"))
                .timeout(Duration.ofSeconds(240))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Voice sidecar TTS failed: HTTP " + response.statusCode() + " "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }
        return response.body();
    }

    @PreDestroy
    public synchronized void shutdown() {
        stopOwnedSidecar();
    }

    private synchronized void ensureStarted() throws IOException, InterruptedException {
        if (isRunningForCurrentConfig()) {
            return;
        }
        if (isRunning()) {
            if (process != null && process.isAlive()) {
                stopOwnedSidecar();
            } else {
                throw new IOException("Voice sidecar is already running on " + endpoint()
                        + " with a different TTS configuration. Stop the old sidecar process and retry.");
            }
        }
        VoiceStatus current = status();
        if (!current.runtimeReady()) {
            throw new IOException("Local voice runtime or models are missing under " + voiceRoot);
        }
        Path script = writeAdapterScript();
        ProcessBuilder builder = new ProcessBuilder(pythonPath().toString(), script.toString());
        builder.directory(voiceRoot.toFile());
        builder.redirectErrorStream(true);
        Path logDir = voiceRoot.resolve("logs");
        Files.createDirectories(logDir);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logDir.resolve("voice-sidecar.log").toFile()));
        Map<String, String> env = builder.environment();
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("JOBCLAW_VOICE_PORT", String.valueOf(port));
        env.put("JOBCLAW_VOICE_ROOT", voiceRoot.toString());
        env.put("JOBCLAW_ASR_MODEL", asrModelPath().toString());
        env.put("JOBCLAW_TTS_ENGINE", ttsEngine());
        env.put("JOBCLAW_TTS_MODEL", ttsModelPath().toString());
        env.put("JOBCLAW_QWEN_TTS_MODEL", qwenTtsModelPath().toString());
        env.put("JOBCLAW_KOKORO_MODEL_DIR", kokoroModelPath().toString());
        env.put("JOBCLAW_VOICE_KEEP_MODELS_LOADED", String.valueOf(keepVoiceModelsLoaded()));
        String ttsDtype = ttsDtype();
        if (!ttsDtype.isBlank()) {
            env.put("JOBCLAW_TTS_DTYPE", ttsDtype);
        }
        env.put("HF_HOME", voiceRoot.resolve("models").resolve("hf-cache").toString());
        env.put("TRANSFORMERS_CACHE", voiceRoot.resolve("models").resolve("hf-cache").resolve("transformers").toString());
        env.put("HF_HUB_OFFLINE", "1");
        env.put("TRANSFORMERS_OFFLINE", "1");
        process = builder.start();
        logger.info("Started local voice sidecar on {} using {}", endpoint(), script);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            if (isRunning()) {
                return;
            }
            if (!process.isAlive()) {
                throw new IOException("Voice sidecar exited during startup. Check " + logDir.resolve("voice-sidecar.log"));
            }
            Thread.sleep(500);
        }
        throw new IOException("Voice sidecar did not become ready within 45 seconds. Check " + logDir.resolve("voice-sidecar.log"));
    }

    private void stopOwnedSidecar() {
        Process owned = process;
        process = null;
        if (owned == null) {
            return;
        }
        try {
            ProcessHandle handle = owned.toHandle();
            handle.descendants().forEach(ProcessHandle::destroy);
            if (owned.isAlive()) {
                owned.destroy();
            }
            if (!owned.waitFor(5, TimeUnit.SECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                owned.destroyForcibly();
                owned.waitFor(5, TimeUnit.SECONDS);
            }
            logger.info("Stopped local voice sidecar on {}", endpoint());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (owned.isAlive()) {
                owned.destroyForcibly();
            }
            logger.warn("Interrupted while stopping local voice sidecar");
        } catch (Exception e) {
            logger.warn("Failed to stop local voice sidecar cleanly: {}", e.getMessage());
        }
    }

    private boolean isRunning() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint() + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isRunningForCurrentConfig() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint() + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return false;
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), new TypeReference<>() {});
            String engine = String.valueOf(body.getOrDefault("ttsEngine", "")).trim().toLowerCase(Locale.ROOT);
            String model = Paths.get(String.valueOf(body.getOrDefault("ttsModel", ""))).toAbsolutePath().normalize().toString();
            return ttsEngine().equals(engine) && ttsModelPath().toString().equalsIgnoreCase(model);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path writeAdapterScript() throws IOException {
        Path adapterDir = voiceRoot.resolve("adapter");
        Files.createDirectories(adapterDir);
        Path script = adapterDir.resolve("voice_sidecar_server.py");
        Files.writeString(script, ADAPTER_SCRIPT, StandardCharsets.UTF_8);
        return script;
    }

    private Path resolveVoiceRoot() {
        String configured = System.getProperty("jobclaw.voice.root");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_VOICE_ROOT");
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get("voice-sidecar-local").toAbsolutePath().normalize();
    }

    private int resolvePort() {
        String value = System.getProperty("jobclaw.voice.port");
        if (value == null || value.isBlank()) {
            value = System.getenv("JOBCLAW_VOICE_PORT");
        }
        if (value != null && !value.isBlank()) {
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                logger.warn("Invalid voice sidecar port: {}", value);
            }
        }
        return DEFAULT_PORT;
    }

    private String endpoint() {
        return "http://127.0.0.1:" + port;
    }

    private Path pythonPath() {
        return voiceRoot.resolve("python").resolve("python.exe");
    }

    private Path asrModelPath() {
        return voiceRoot.resolve("models").resolve("asr").resolve("faster-whisper-small");
    }

    private String ttsEngine() {
        String configured = System.getProperty("jobclaw.voice.ttsEngine");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_TTS_ENGINE");
        }
        if (configured != null && !configured.isBlank()) {
            return configured.trim().toLowerCase(Locale.ROOT);
        }
        return Files.exists(kokoroModelPath().resolve("kokoro-v1_1-zh.pth")) ? "kokoro" : "qwen";
    }

    private Path ttsModelPath() {
        String configured = System.getProperty("jobclaw.voice.ttsModel");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_TTS_MODEL");
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return "kokoro".equals(ttsEngine()) ? kokoroModelPath() : qwenTtsModelPath();
    }

    private Path qwenTtsModelPath() {
        String configured = System.getProperty("jobclaw.voice.qwenTtsModel");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_QWEN_TTS_MODEL");
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path preferred = voiceRoot.resolve("models").resolve("tts").resolve("Qwen3-TTS-12Hz-0.6B-CustomVoice");
        if (Files.exists(preferred.resolve("model.safetensors"))) {
            return preferred;
        }
        return voiceRoot.resolve("models").resolve("tts").resolve("Qwen3-TTS-12Hz-1.7B-CustomVoice");
    }

    private Path kokoroModelPath() {
        String configured = System.getProperty("jobclaw.voice.kokoroModel");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_KOKORO_MODEL_DIR");
        }
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return voiceRoot.resolve("models").resolve("tts").resolve("Kokoro-82M-v1.1-zh");
    }

    private boolean ttsRuntimeReady() {
        if ("kokoro".equals(ttsEngine())) {
            return Files.exists(kokoroModelPath().resolve("kokoro-v1_1-zh.pth"))
                    && Files.exists(kokoroModelPath().resolve("config.json"));
        }
        return Files.exists(qwenTtsModelPath().resolve("model.safetensors"));
    }

    private String ttsDtype() {
        String configured = System.getProperty("jobclaw.voice.ttsDtype");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_TTS_DTYPE");
        }
        return configured == null ? "" : configured.trim();
    }

    private boolean keepVoiceModelsLoaded() {
        String configured = System.getProperty("jobclaw.voice.keepModelsLoaded");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("JOBCLAW_VOICE_KEEP_MODELS_LOADED");
        }
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured.trim());
        }
        return "kokoro".equals(ttsEngine())
                || qwenTtsModelPath().getFileName().toString().toLowerCase(Locale.ROOT).contains("0.6b");
    }

    private List<String> defaultVoices() {
        if (!"kokoro".equals(ttsEngine())) {
            return QWEN_VOICES;
        }
        Path voicesDir = kokoroModelPath().resolve("voices");
        if (!Files.isDirectory(voicesDir)) {
            return FALLBACK_KOKORO_VOICES;
        }
        List<String> voices = new ArrayList<>();
        try (var stream = Files.list(voicesDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".pt"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.pt$", ""))
                    .sorted()
                    .forEach(voices::add);
        } catch (IOException ignored) {
            return FALLBACK_KOKORO_VOICES;
        }
        return voices.isEmpty() ? FALLBACK_KOKORO_VOICES : voices;
    }

    private String defaultVoice() {
        return "kokoro".equals(ttsEngine()) ? "zf_001" : "Vivian";
    }

    private String normalizeVoice(String voice) {
        if (voice == null || voice.isBlank()) {
            return defaultVoice();
        }
        String requested = voice.trim();
        for (String candidate : defaultVoices()) {
            if (candidate.equalsIgnoreCase(requested)) {
                return candidate;
            }
        }
        return defaultVoice();
    }

    private double numberValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(value.toString());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private void ensureSuccess(int statusCode, String body) throws IOException {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Voice sidecar failed: HTTP " + statusCode + " " + body);
        }
    }

    public record VoiceStatus(boolean runtimeReady,
                              boolean running,
                              String status,
                              String endpoint,
                              String root,
                              String asrModel,
                              String ttsModel,
                              List<String> voices,
                              String defaultVoice) {
    }

    public record TranscriptionResult(String text, String language, double languageProbability) {
    }

    private static final String ADAPTER_SCRIPT = """
import gc
import io
import json
import os
import tempfile
import threading
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

os.environ.setdefault('PYTHONIOENCODING', 'utf-8')
os.environ.setdefault('HF_HUB_OFFLINE', '1')
os.environ.setdefault('TRANSFORMERS_OFFLINE', '1')

PORT = int(os.environ.get('JOBCLAW_VOICE_PORT', '18792'))
ROOT = Path(os.environ.get('JOBCLAW_VOICE_ROOT', '.')).resolve()
ASR_MODEL = Path(os.environ.get('JOBCLAW_ASR_MODEL', ROOT / 'models' / 'asr' / 'faster-whisper-small')).resolve()
TTS_ENGINE = os.environ.get('JOBCLAW_TTS_ENGINE', 'kokoro').strip().lower()
TTS_MODEL = Path(os.environ.get('JOBCLAW_TTS_MODEL', ROOT / 'models' / 'tts' / 'Kokoro-82M-v1.1-zh')).resolve()
QWEN_TTS_MODEL = Path(os.environ.get('JOBCLAW_QWEN_TTS_MODEL', ROOT / 'models' / 'tts' / 'Qwen3-TTS-12Hz-0.6B-CustomVoice')).resolve()
KOKORO_MODEL_DIR = Path(os.environ.get('JOBCLAW_KOKORO_MODEL_DIR', ROOT / 'models' / 'tts' / 'Kokoro-82M-v1.1-zh')).resolve()
TTS_DTYPE = os.environ.get('JOBCLAW_TTS_DTYPE', '').strip().lower()
KEEP_MODELS_LOADED = os.environ.get('JOBCLAW_VOICE_KEEP_MODELS_LOADED', '').strip().lower() in ('1', 'true', 'yes', 'on')
QWEN_VOICES = ['Vivian', 'Serena', 'Uncle_Fu', 'Dylan', 'Eric', 'Ryan', 'Aiden', 'Ono_Anna', 'Sohee']
FALLBACK_KOKORO_VOICES = ['zf_001', 'zf_002', 'zf_003', 'zf_004', 'zf_005', 'zm_010']

def list_kokoro_voices():
    voices_dir = KOKORO_MODEL_DIR / 'voices'
    if not voices_dir.is_dir():
        return FALLBACK_KOKORO_VOICES
    voices = sorted(p.stem for p in voices_dir.glob('*.pt'))
    return voices or FALLBACK_KOKORO_VOICES

VOICES = list_kokoro_voices() if TTS_ENGINE == 'kokoro' else QWEN_VOICES
DEFAULT_VOICE = 'zf_001' if TTS_ENGINE == 'kokoro' else 'Vivian'

class ModelManager:
    def __init__(self):
        self.lock = threading.Lock()
        self.mode = None
        self.asr = None
        self.qwen_tts = None
        self.kokoro_model = None
        self.kokoro_pipeline = None
        self.kokoro_en_pipeline = None
        self.torch = None

    def _torch(self):
        if self.torch is None:
            import torch
            self.torch = torch
        return self.torch

    def _cleanup_cuda(self):
        try:
            torch = self._torch()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass
        gc.collect()

    def unload(self):
        self.asr = None
        self.qwen_tts = None
        self.kokoro_model = None
        self.kokoro_pipeline = None
        self.kokoro_en_pipeline = None
        self.mode = None
        self._cleanup_cuda()

    def load_asr(self):
        if self.asr is not None:
            return self.asr
        if not KEEP_MODELS_LOADED:
            self.unload()
        from faster_whisper import WhisperModel
        print('[voice-sidecar] loading ASR', ASR_MODEL, 'keep_models_loaded', KEEP_MODELS_LOADED, flush=True)
        self.asr = WhisperModel(str(ASR_MODEL), device='cuda', compute_type='float16')
        self.mode = 'asr'
        return self.asr

    def load_tts(self):
        if TTS_ENGINE == 'kokoro':
            return self.load_kokoro_tts()
        return self.load_qwen_tts()

    def load_qwen_tts(self):
        if self.qwen_tts is not None:
            return self.qwen_tts
        if not KEEP_MODELS_LOADED:
            self.unload()
        torch = self._torch()
        from qwen_tts import Qwen3TTSModel
        dtype = self._tts_dtype(torch)
        print('[voice-sidecar] loading Qwen TTS', QWEN_TTS_MODEL, 'dtype', dtype, 'keep_models_loaded', KEEP_MODELS_LOADED, flush=True)
        self.qwen_tts = Qwen3TTSModel.from_pretrained(
            str(QWEN_TTS_MODEL),
            device_map='cuda:0',
            dtype=dtype,
            attn_implementation=None,
        )
        self.mode = 'tts'
        return self.qwen_tts

    def load_kokoro_tts(self):
        if self.kokoro_pipeline is not None:
            return self.kokoro_pipeline
        if not KEEP_MODELS_LOADED:
            self.unload()
        torch = self._torch()
        from kokoro import KModel, KPipeline
        device = 'cuda' if torch.cuda.is_available() else 'cpu'
        print('[voice-sidecar] loading Kokoro TTS', KOKORO_MODEL_DIR, 'device', device, 'keep_models_loaded', KEEP_MODELS_LOADED, flush=True)
        self.kokoro_model = KModel(
            repo_id=str(KOKORO_MODEL_DIR),
            config=str(KOKORO_MODEL_DIR / 'config.json'),
            model=str(KOKORO_MODEL_DIR / 'kokoro-v1_1-zh.pth'),
        ).to(device).eval()
        self.kokoro_pipeline = KPipeline(
            lang_code='z',
            repo_id=str(KOKORO_MODEL_DIR),
            model=self.kokoro_model,
            en_callable=self.kokoro_en_callable(),
        )
        self.mode = 'tts'
        return self.kokoro_pipeline

    def kokoro_en_callable(self):
        try:
            from kokoro import KPipeline
            if self.kokoro_en_pipeline is None:
                self.kokoro_en_pipeline = KPipeline(lang_code='a', repo_id=str(KOKORO_MODEL_DIR), model=False)
            def convert(text):
                phonemes, _ = self.kokoro_en_pipeline.g2p(text)
                return phonemes or ''
            return convert
        except Exception as exc:
            print('[voice-sidecar] English G2P unavailable, mixed English may be skipped:', exc, flush=True)
            return None

    def _tts_dtype(self, torch):
        if TTS_DTYPE in ('bf16', 'bfloat16'):
            return torch.bfloat16
        if TTS_DTYPE in ('fp16', 'float16'):
            return torch.float16
        model_name = str(QWEN_TTS_MODEL).lower()
        if '0.6b' in model_name:
            return torch.bfloat16
        return torch.float16

manager = ModelManager()

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        print('[voice-sidecar]', fmt % args, flush=True)

    def _send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, status, content_type, body):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        length = int(self.headers.get('Content-Length', '0'))
        return self.rfile.read(length) if length > 0 else b''

    def do_GET(self):
        if self.path == '/health':
            self._send_json(200, {'ok': True, 'mode': manager.mode, 'asrModel': str(ASR_MODEL), 'ttsModel': str(TTS_MODEL), 'ttsEngine': TTS_ENGINE})
            return
        if self.path == '/voices':
            self._send_json(200, {'voices': VOICES, 'defaultVoice': DEFAULT_VOICE})
            return
        self._send_json(404, {'error': 'not found'})

    def do_POST(self):
        try:
            if self.path == '/asr':
                self.handle_asr()
                return
            if self.path == '/tts':
                self.handle_tts()
                return
            self._send_json(404, {'error': 'not found'})
        except Exception as exc:
            traceback.print_exc()
            self._send_json(500, {'error': str(exc)})

    def handle_asr(self):
        body = self._read_body()
        if not body:
            self._send_json(400, {'error': 'audio body is required'})
            return
        with manager.lock:
            with tempfile.NamedTemporaryFile(prefix='jobclaw_asr_', suffix='.wav', delete=False) as tmp:
                tmp.write(body)
                audio_path = tmp.name
            try:
                asr = manager.load_asr()
                segments, info = asr.transcribe(audio_path, language='zh', beam_size=1)
                text = ''.join(seg.text for seg in segments).strip()
                self._send_json(200, {
                    'text': text,
                    'language': getattr(info, 'language', 'zh'),
                    'languageProbability': float(getattr(info, 'language_probability', 0.0) or 0.0),
                })
            finally:
                try:
                    os.remove(audio_path)
                except OSError:
                    pass

    def handle_tts(self):
        payload = json.loads(self._read_body().decode('utf-8'))
        text = str(payload.get('text', '')).strip()
        voice = str(payload.get('voice', DEFAULT_VOICE)).strip() or DEFAULT_VOICE
        language = str(payload.get('language', 'Chinese')).strip() or 'Chinese'
        instruct = payload.get('instruct') or '用自然清晰的普通话朗读。'
        if not text:
            self._send_json(400, {'error': 'text is required'})
            return
        if not any(v.lower() == voice.lower() for v in VOICES):
            voice = DEFAULT_VOICE
        with manager.lock:
            tts = manager.load_tts()
            if TTS_ENGINE == 'kokoro':
                voice_path = KOKORO_MODEL_DIR / 'voices' / f'{voice}.pt'
                result = next(tts(text, voice=str(voice_path), speed=1.0))
                wav = result.audio
                sr = 24000
            else:
                wavs, sr = tts.generate_custom_voice(
                    text=text,
                    language=language,
                    speaker=voice,
                    instruct=instruct,
                    max_new_tokens=768,
                )
                wav = wavs[0]
            import soundfile as sf
            buf = io.BytesIO()
            sf.write(buf, wav, sr, format='WAV')
            self._send_bytes(200, 'audio/wav', buf.getvalue())

if __name__ == '__main__':
    print(f'[voice-sidecar] starting on 127.0.0.1:{PORT}', flush=True)
    print(f'[voice-sidecar] ASR={ASR_MODEL}', flush=True)
    print(f'[voice-sidecar] TTS_ENGINE={TTS_ENGINE}', flush=True)
    print(f'[voice-sidecar] TTS={TTS_MODEL}', flush=True)
    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    server.serve_forever()
""";
}
