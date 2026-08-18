package io.jobclaw.runtime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ToolCallRepeatGuard {

    private static final int MAX_REUSED_RESULT_CHARS = 4_000;
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final Map<String, SequenceState> runStates = new ConcurrentHashMap<>();

    Decision beforeCall(String sessionKey,
                        String runId,
                        String toolName,
                        String request,
                        boolean enabled,
                        int threshold) {
        if (!enabled) {
            return Decision.allow();
        }
        int effectiveThreshold = Math.max(2, threshold);
        String runKey = runKey(sessionKey, runId);
        String signature = signature(toolName, request);
        SequenceState state = runStates.get(runKey);
        if (state == null
                || !state.signature.equals(signature)
                || state.stableResultCount < effectiveThreshold - 1) {
            return Decision.allow();
        }
        return Decision.block(formatBlockedResponse(toolName, state.lastModelResponse));
    }

    void recordResult(String sessionKey,
                      String runId,
                      String toolName,
                      String request,
                      String rawResponse,
                      String modelResponse) {
        String runKey = runKey(sessionKey, runId);
        String signature = signature(toolName, request);
        String resultFingerprint = fingerprint(rawResponse);
        runStates.compute(runKey, (ignored, previous) -> {
            if (previous == null || !previous.signature.equals(signature)) {
                return new SequenceState(signature, resultFingerprint, 1, modelResponse);
            }
            if (!previous.resultFingerprint.equals(resultFingerprint)) {
                return new SequenceState(signature, resultFingerprint, 1, modelResponse);
            }
            return new SequenceState(
                    signature,
                    resultFingerprint,
                    previous.stableResultCount + 1,
                    modelResponse
            );
        });
    }

    void clear(String sessionKey, String runId) {
        runStates.remove(runKey(sessionKey, runId));
    }

    private String signature(String toolName, String request) {
        return (toolName != null ? toolName.trim().toLowerCase() : "")
                + "\n"
                + canonicalRequest(request);
    }

    private String canonicalRequest(String request) {
        if (request == null || request.isBlank()) {
            return "{}";
        }
        try {
            Object value = CANONICAL_MAPPER.readValue(request, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(value);
        } catch (Exception ignored) {
            return request.trim().replaceAll("\\s+", " ");
        }
    }

    private String fingerprint(String response) {
        String value = response != null ? response : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private String formatBlockedResponse(String toolName, String previousResult) {
        String result = previousResult != null ? previousResult : "";
        if (result.length() > MAX_REUSED_RESULT_CHARS) {
            result = result.substring(0, MAX_REUSED_RESULT_CHARS)
                    + "\n...[previous result shortened; use its context_ref when available]";
        }
        return """
                REPETITION_DETECTED
                The same tool call has already produced the same result repeatedly and was not executed again.
                tool: %s

                Reuse the previous result below. Change the arguments, choose another approach, or request user input if progress is blocked.

                previous_result:
                %s
                """.formatted(toolName != null ? toolName : "", result);
    }

    private String runKey(String sessionKey, String runId) {
        return (sessionKey != null ? sessionKey : "no-session")
                + ":"
                + (runId != null ? runId : "no-run");
    }

    record Decision(boolean blocked, String response) {
        static Decision allow() {
            return new Decision(false, "");
        }

        static Decision block(String response) {
            return new Decision(true, response);
        }
    }

    private record SequenceState(
            String signature,
            String resultFingerprint,
            int stableResultCount,
            String lastModelResponse
    ) {
    }
}
