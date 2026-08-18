package io.jobclaw.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentHarnessReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldReplayRecordedStreamShapes() throws Exception {
        List<StreamReplayScenario> scenarios;
        try (InputStream input = resource("harness/replays/agent/stream-deltas.json")) {
            scenarios = MAPPER.readValue(input, new TypeReference<>() {
            });
        }

        for (StreamReplayScenario scenario : scenarios) {
            AgentLoop.StreamDeltaNormalizer normalizer = new AgentLoop.StreamDeltaNormalizer();
            StringBuilder accumulated = new StringBuilder();
            List<String> deltas = new ArrayList<>();
            for (String chunk : scenario.chunks()) {
                String delta = normalizer.normalize(accumulated, chunk);
                deltas.add(delta);
                accumulated.append(delta);
            }
            assertEquals(scenario.expectedDeltas(), deltas, scenario.id());
            assertEquals(scenario.expectedFinal(), accumulated.toString(), scenario.id());
        }
    }

    private InputStream resource(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        if (input == null) {
            throw new IllegalArgumentException("Missing replay resource: " + path);
        }
        return input;
    }

    private record StreamReplayScenario(
            String id,
            List<String> chunks,
            List<String> expectedDeltas,
            String expectedFinal
    ) {
    }
}
