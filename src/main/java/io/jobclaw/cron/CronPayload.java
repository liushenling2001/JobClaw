package io.jobclaw.cron;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cron 任务负载
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CronPayload {
    
    private String message;  // 消息内容
    private String channel;  // 目标通道
    private String to;       // 目标接收者
    
    public CronPayload() {
    }
    
    public CronPayload(String message, String channel, String to) {
        this.message = message;
        this.channel = channel;
        this.to = to;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public String getChannel() {
        return channel;
    }
    
    public void setChannel(String channel) {
        this.channel = channel;
    }
    
    public String getTo() {
        return to;
    }
    
    public void setTo(String to) {
        this.to = to;
    }
}
