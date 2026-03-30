package io.jobclaw.conversation;

import java.util.List;
import java.util.Optional;

public interface ConversationStore {

    void appendMessage(StoredMessage message);

    List<StoredMessage> listRecentMessages(String sessionId, int limit);

    List<StoredMessage> listMessages(String sessionId, int offset, int limit);

    Optional<SessionRecord> getSession(String sessionId);

    List<SessionRecord> listSessions();

    void deleteSession(String sessionId);

    void saveChunk(MessageChunk chunk);

    List<MessageChunk> listChunks(String sessionId);
}
