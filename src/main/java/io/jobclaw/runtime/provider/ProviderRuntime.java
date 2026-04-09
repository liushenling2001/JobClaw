package io.jobclaw.runtime.provider;

import io.jobclaw.config.Config;
import io.jobclaw.config.ProvidersConfig;
import org.springframework.stereotype.Component;

@Component
public class ProviderRuntime {

    public ResolvedProviderConfig resolve(Config config, String explicitModelOverride) {
        String requestedProviderName = config.getAgent() != null ? config.getAgent().getProvider() : null;
        ProvidersConfig.ProviderConfig providerConfig = config.getProviderConfigByName(requestedProviderName);
        boolean fallbackUsed = false;
        String effectiveProviderName = requestedProviderName;

        if (providerConfig == null) {
            providerConfig = config.getProviders().getFirstValidProvider().orElse(null);
            if (providerConfig != null) {
                effectiveProviderName = config.getProviders().getProviderName(providerConfig);
                fallbackUsed = true;
            }
        }

        if (providerConfig == null) {
            throw new IllegalStateException("No valid provider configuration found");
        }

        String model = explicitModelOverride != null ? explicitModelOverride : config.getAgent().getModel();
        String apiKey = providerConfig.getApiKey();
        String apiBase = resolveApiBase(effectiveProviderName, providerConfig);
        String springAiBaseUrl = toSpringAiBaseUrl(apiBase);

        return new ResolvedProviderConfig(
                effectiveProviderName,
                model,
                apiKey,
                apiBase,
                springAiBaseUrl,
                fallbackUsed
        );
    }

    private String resolveApiBase(String providerName, ProvidersConfig.ProviderConfig providerConfig) {
        String apiBase = providerConfig.getApiBase();
        if (apiBase == null || apiBase.isEmpty()) {
            return ProvidersConfig.getDefaultApiBase(providerName);
        }
        return apiBase;
    }

    private String toSpringAiBaseUrl(String apiBase) {
        return apiBase.replaceAll("/v1$", "");
    }
}
