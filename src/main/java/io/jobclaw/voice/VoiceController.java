package io.jobclaw.voice;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceSidecarService voiceSidecarService;

    public VoiceController(VoiceSidecarService voiceSidecarService) {
        this.voiceSidecarService = voiceSidecarService;
    }

    @GetMapping("/status")
    public ResponseEntity<VoiceSidecarService.VoiceStatus> status() {
        return ResponseEntity.ok(voiceSidecarService.status());
    }

    @GetMapping("/voices")
    public ResponseEntity<Map<String, Object>> voices() {
        VoiceSidecarService.VoiceStatus status = voiceSidecarService.status();
        return ResponseEntity.ok(Map.of(
                "voices", voiceSidecarService.voices(),
                "defaultVoice", status.defaultVoice()
        ));
    }

    @PostMapping(value = "/transcribe", consumes = "audio/wav", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VoiceSidecarService.TranscriptionResult> transcribe(@RequestBody byte[] audio) throws Exception {
        return ResponseEntity.ok(voiceSidecarService.transcribe(audio));
    }

    @PostMapping(value = "/tts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> tts(@RequestBody TtsRequest request) throws Exception {
        byte[] wav = voiceSidecarService.synthesize(request.text(), request.voice());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(wav);
    }

    public record TtsRequest(String text, String voice) {
    }
}
