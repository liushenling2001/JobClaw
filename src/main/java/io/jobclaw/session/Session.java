package io.jobclaw.session;

import io.jobclaw.providers.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话 - 表示一次对话
 */
public class Session {

    private String key;
    private List<Message> messages;
    private String summary;
    private Instant created;
    private Instant updated;

    public Session() {
        this.messages = new ArrayList<>();
        this.created = Instant.now();
        this.updated = Instant.now();
    }

    public Session(String key) {
        this();
        this.key = key;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }
    public Instant getUpdated() { return updated; }
    public void setUpdated(Instant updated) { this.updated = updated; }

    public void addMessage(String role, String content) {
        Message msg = new Message(role, content);
        this.messages.add(msg);
        this.updated = Instant.now();
    }

    public void addFullMessage(Message message) {
        this.messages.add(message);
        this.updated = Instant.now();
    }

    public List<Message> getHistory() {
        return new ArrayList<>(messages);
    }

    public void truncateHistory(int keepLast) {
        if (messages.size() <= keepLast) {
            return;
        }

        int startIndex = messages.size() - keepLast;
        startIndex = adjustStartIndexForToolMessageIntegrity(startIndex);

        messages = new ArrayList<>(messages.subList(startIndex, messages.size()));
        this.updated = Instant.now();
    }

    private int adjustStartIndexForToolMessageIntegrity(int startIndex) {
        if (startIndex <= 0 || startIndex >= messages.size()) {
            return startIndex;
        }

        Message startMessage = messages.get(startIndex);
        if (!"tool".equals(startMessage.getRole())) {
            return startIndex;
        }

        for (int i = startIndex - 1; i >= 0; i--) {
            Message candidate = messages.get(i);
            if ("assistant".equals(candidate.getRole())
                    && candidate.getToolCalls() != null
                    && !candidate.getToolCalls().isEmpty()) {
                return i;
            }
            if (!"tool".equals(candidate.getRole())) {
                break;
            }
        }

        int adjusted = startIndex;
        while (adjusted < messages.size() && "tool".equals(messages.get(adjusted).getRole())) {
            adjusted++;
        }
        return adjusted;
    }
}
