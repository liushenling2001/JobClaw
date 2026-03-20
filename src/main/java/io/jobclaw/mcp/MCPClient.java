package io.jobclaw.mcp;

import java.io.IOException;
import java.util.Map;

public interface MCPClient {

    void connect() throws IOException;

    MCPMessage sendRequest(String method, Map<String, Object> params) throws Exception;

    void sendNotification(String method, Map<String, Object> params) throws IOException;

    void close();

    boolean isConnected();

    class MCPException extends Exception {
        private final int code;

        public MCPException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
