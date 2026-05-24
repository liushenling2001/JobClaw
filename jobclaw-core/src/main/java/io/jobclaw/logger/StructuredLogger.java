package io.jobclaw.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.Map;

/**
 * 结构化日志工具类
 */
public class StructuredLogger {

    private final Logger logger;

    public StructuredLogger(String name) {
        this.logger = LoggerFactory.getLogger(name);
    }

    public void info(String message) {
        logger.info(message);
    }

    public void info(String message, Map<String, Object> context) {
        logger.info(formatWithContext(message, context));
    }

    public void debug(String message) {
        logger.debug(message);
    }

    public void debug(String message, Map<String, Object> context) {
        logger.debug(formatWithContext(message, context));
    }

    public void warn(String message) {
        logger.warn(message);
    }

    public void warn(String message, Map<String, Object> context) {
        logger.warn(formatWithContext(message, context));
    }

    public void error(String message) {
        logger.error(message);
    }

    public void error(String message, Map<String, Object> context) {
        logger.error(formatWithContext(message, context));
    }

    public void error(String message, Throwable t) {
        logger.error(message, t);
    }

    private String formatWithContext(String message, Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        sb.append(" {");
        boolean first = true;
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    public static StructuredLogger getLogger(String name) {
        return new StructuredLogger(name);
    }
}
