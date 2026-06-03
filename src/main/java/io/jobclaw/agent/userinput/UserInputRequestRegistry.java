package io.jobclaw.agent.userinput;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class UserInputRequestRegistry {

    private static final ConcurrentMap<String, UserInputRequest> PENDING = new ConcurrentHashMap<>();

    public UserInputRequest request(String sessionKey,
                                    String runId,
                                    String question,
                                    String reason,
                                    String requiredFor,
                                    String resumeKey,
                                    List<String> options) {
        String normalizedSession = normalize(sessionKey, "no-session");
        String normalizedRun = normalize(runId, "no-run");
        String key = key(normalizedSession, normalizedRun);
        UserInputRequest request = new UserInputRequest(
                normalizedSession,
                normalizedRun,
                "ui-" + UUID.randomUUID().toString().substring(0, 8),
                normalize(question, "Please provide the required input to continue."),
                normalize(reason, ""),
                normalize(requiredFor, "decision"),
                normalize(resumeKey, ""),
                options != null ? List.copyOf(options) : List.of(),
                Instant.now()
        );
        UserInputRequest existing = PENDING.putIfAbsent(key, request);
        return existing != null ? existing : request;
    }

    public Optional<UserInputRequest> getPending(String sessionKey, String runId) {
        return Optional.ofNullable(PENDING.get(key(sessionKey, runId)));
    }

    public void clear(String sessionKey, String runId) {
        PENDING.remove(key(sessionKey, runId));
    }

    public void clearSession(String sessionKey) {
        String prefix = normalize(sessionKey, "no-session") + "::";
        List<String> keys = new ArrayList<>(PENDING.keySet());
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                PENDING.remove(key);
            }
        }
    }

    private String key(String sessionKey, String runId) {
        return normalize(sessionKey, "no-session") + "::" + normalize(runId, "no-run");
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
