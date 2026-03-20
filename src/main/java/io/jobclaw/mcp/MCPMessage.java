package io.jobclaw.mcp;

import java.util.HashMap;
import java.util.Map;

public class MCPMessage {

    private String jsonrpc = "2.0";
    private String method;
    private Map<String, Object> params;
    private Object result;
    private Error error;
    private String id;

    public static class Error {
        private int code;
        private String message;

        public int getCode() { return code; }
        public void setCode(int code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public String getJsonrpc() { return jsonrpc; }
    public void setJsonrpc(String jsonrpc) { this.jsonrpc = jsonrpc; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public Error getError() { return error; }
    public void setError(Error error) { this.error = error; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public boolean hasError() {
        return error != null;
    }

    public static MCPMessage createRequest(String id, String method, Map<String, Object> params) {
        MCPMessage msg = new MCPMessage();
        msg.setId(id);
        msg.setJsonrpc("2.0");
        msg.setMethod(method);
        msg.setParams(params != null ? params : new HashMap<>());
        return msg;
    }

    public static MCPMessage createNotification(String method, Map<String, Object> params) {
        MCPMessage msg = new MCPMessage();
        msg.setJsonrpc("2.0");
        msg.setMethod(method);
        msg.setParams(params != null ? params : new HashMap<>());
        return msg;
    }

    public static MCPMessage createResponse(String id, Object result) {
        MCPMessage msg = new MCPMessage();
        msg.setId(id);
        msg.setJsonrpc("2.0");
        msg.setResult(result);
        return msg;
    }

    public static MCPMessage createError(String id, int code, String message) {
        MCPMessage msg = new MCPMessage();
        msg.setId(id);
        msg.setJsonrpc("2.0");
        Error error = new Error();
        error.setCode(code);
        error.setMessage(message);
        msg.setError(error);
        return msg;
    }
}
