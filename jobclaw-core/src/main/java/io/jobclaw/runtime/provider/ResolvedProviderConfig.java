package io.jobclaw.runtime.provider;

public record ResolvedProviderConfig(
        String providerName,
        String model,
        String apiKey,
        String apiBase,
        String springAiBaseUrl,
        boolean fallbackUsed
) {
}
