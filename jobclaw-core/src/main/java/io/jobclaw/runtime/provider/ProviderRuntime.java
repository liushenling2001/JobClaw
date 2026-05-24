package io.jobclaw.runtime.provider;

import io.jobclaw.config.Config;
import io.jobclaw.config.ProvidersConfig;
import org.springframework.stereotype.Component;

@Component
public class ProviderRuntime {

    public ResolvedProviderConfig resolve(Config config, String explicitModelOverride) {
        return resolve(config, null, null, explicitModelOverride);
    }

    public ResolvedProviderConfig resolve(Config config,
                                          String explicitProviderOverride,
                                          String explicitApiBaseOverride,
                                          String explicitModelOverride) {
        String requestedProviderName = config.getAgent() != null ? config.getAgent().getProvider() : null;
        if (explicitProviderOverride != null && !explicitProviderOverride.isBlank()) {
            requestedProviderName = explicitProviderOverride;
        }
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
        if (!isProviderUsable(effectiveProviderName, providerConfig)) {
            throw new IllegalStateException("Provider '" + effectiveProviderName
                    + "' is configured but missing apiKey. Configure providers."
                    + effectiveProviderName + ".apiKey or choose a local provider such as ollama.");
        }

        String model = explicitModelOverride != null ? explicitModelOverride : config.getAgent().getModel();
        String apiKey = providerConfig.getApiKey();
        String apiBase = explicitApiBaseOverride != null && !explicitApiBaseOverride.isBlank()
                ? explicitApiBaseOverride
                : resolveApiBase(effectiveProviderName, providerConfig);
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

    private boolean isProviderUsable(String providerName, ProvidersConfig.ProviderConfig providerConfig) {
        if (providerConfig == null) {
            return false;
        }
        if ("ollama".equalsIgnoreCase(providerName)) {
            return providerConfig.hasApiBase();
        }
        return providerConfig.isValid();
    }
}
