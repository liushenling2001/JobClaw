package io.jobclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronPayload {

    private String kind;
    private String message;
    private String channel;
    private String to;

    public CronPayload() {}

    public CronPayload(String message, String channel, String to) {
        this.kind = "agent_turn";
        this.message = message;
        this.channel = channel;
        this.to = to;
    }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
}
