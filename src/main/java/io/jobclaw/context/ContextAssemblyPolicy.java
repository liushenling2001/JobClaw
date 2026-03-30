package io.jobclaw.context;

public interface ContextAssemblyPolicy {

    ContextAssemblyOptions buildOptions(String sessionId, String currentUserInput);
}
