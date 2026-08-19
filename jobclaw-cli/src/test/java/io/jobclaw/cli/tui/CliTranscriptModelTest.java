package io.jobclaw.cli.tui;

import io.jobclaw.agent.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliTranscriptModelTest {

    @Test
    void keepsReasoningOutOfTheVisibleAssistantResponse() {
        CliTranscriptModel model = new CliTranscriptModel();
        model.addUser("question");

        model.accept(stream("hidden", "seg-1", true));
        model.accept(stream("visible", "seg-1", false));
        model.accept(new ExecutionEvent("session", ExecutionEvent.EventType.FINAL_RESPONSE, "visible"));

        assertEquals(2, model.blocks().size());
        CliTranscriptModel.TextBlock assistant = (CliTranscriptModel.TextBlock) model.blocks().get(1);
        assertEquals("visible", assistant.text());
    }

    @Test
    void startsANewAssistantBlockForANewStreamSegment() {
        CliTranscriptModel model = new CliTranscriptModel();

        model.accept(stream("before tool", "seg-1", false));
        model.accept(new ExecutionEvent("session", ExecutionEvent.EventType.TOOL_START, "",
                Map.of("toolId", "call-1", "toolName", "read_file")));
        model.accept(new ExecutionEvent("session", ExecutionEvent.EventType.TOOL_END, "done",
                Map.of("toolId", "call-1", "toolName", "read_file")));
        model.accept(stream("after tool", "seg-2", false));

        assertEquals(3, model.blocks().size());
        assertEquals("before tool", ((CliTranscriptModel.TextBlock) model.blocks().get(0)).text());
        assertEquals("after tool", ((CliTranscriptModel.TextBlock) model.blocks().get(2)).text());
    }

    @Test
    void ignoresEmptyLeadingDeltasAndRemovesLeadingLineBreaks() {
        CliTranscriptModel model = new CliTranscriptModel();

        model.accept(stream("", "seg-1", false));
        model.accept(stream("\n\nanswer", "seg-1", false));

        assertEquals(1, model.blocks().size());
        assertEquals("answer", ((CliTranscriptModel.TextBlock) model.blocks().get(0)).text());
    }

    private ExecutionEvent stream(String content, String segmentId, boolean reasoning) {
        return new ExecutionEvent("session", ExecutionEvent.EventType.THINK_STREAM, content,
                Map.of("streamSegmentId", segmentId, "reasoning", reasoning));
    }
}
