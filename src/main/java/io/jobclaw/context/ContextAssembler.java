package io.jobclaw.context;

import io.jobclaw.providers.Message;

import java.util.List;

public interface ContextAssembler {

    List<Message> assemble(
            String sessionId,
            String currentUserInput,
            ContextAssemblyOptions options
    );
}
