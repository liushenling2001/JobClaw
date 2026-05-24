package io.jobclaw.channels;

/**
 * Channel exception for signaling channel-related errors.
 */
public class ChannelException extends RuntimeException {

    public ChannelException(String message) {
        super(message);
    }

    public ChannelException(String message, Throwable cause) {
        super(message, cause);
    }
}
