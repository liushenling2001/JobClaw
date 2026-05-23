package io.jobclaw.run;

public enum RunStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_INPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTING,
    INTERRUPTED,
    RECOVERING
}
