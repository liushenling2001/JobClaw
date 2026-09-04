package io.jobclaw.web;

import io.jobclaw.runtime.provider.ModelDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/providers")
@CrossOrigin(origins = "*")
public class ModelDiscoveryController {

    private static final Logger logger = LoggerFactory.getLogger(ModelDiscoveryController.class);

    private final ModelDiscoveryService modelDiscoveryService;

    public ModelDiscoveryController(ModelDiscoveryService modelDiscoveryService) {
        this.modelDiscoveryService = modelDiscoveryService;
    }

    @GetMapping("/{provider}/models")
    public ResponseEntity<?> discoverModels(@PathVariable String provider) {
        return discoverModels(provider, null);
    }

    @PostMapping("/{provider}/models/discover")
    public ResponseEntity<?> discoverModels(@PathVariable String provider,
                                            @RequestBody(required = false) DiscoveryRequest request) {
        try {
            String apiBase = request == null ? null : request.apiBase();
            String apiKey = request == null ? null : request.apiKey();
            return ResponseEntity.ok(modelDiscoveryService.discover(provider, apiBase, apiKey));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (ModelDiscoveryService.ModelDiscoveryException e) {
            logger.warn("Model discovery failed for provider={}: {}", provider, e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", e.getMessage(),
                    "providerStatus", e.getStatusCode()
            ));
        } catch (Exception e) {
            logger.warn("Model discovery failed for provider={}", provider, e);
            return ResponseEntity.status(502).body(Map.of("error", "Model discovery failed: " + e.getMessage()));
        }
    }

    public record DiscoveryRequest(String apiBase, String apiKey) {
    }
}
