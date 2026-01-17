package com.vrctool.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vrctool.bot.config.BotConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class LlmHttpClient implements LlmClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final BotConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmHttpClient(BotConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public LlmClassification classifyMessage(String content, LlmRuleContext ruleContext) {
        if (!config.llmClassificationEnabled()) {
            return new LlmClassification(RiskLevel.LOW, "LLM classification disabled.");
        }
        String endpoint = config.llmEndpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            return classifyByRules(ruleContext);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestPayload(content)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return classifyByRules(ruleContext);
            }
            return parseResponse(response.body(), ruleContext);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return classifyByRules(ruleContext);
        } catch (IOException | IllegalArgumentException error) {
            return classifyByRules(ruleContext);
        }
    }

    private String buildRequestPayload(String content) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", content);
        payload.put("format", "risk");
        payload.put("response_format", "json");
        return objectMapper.writeValueAsString(payload);
    }

    private LlmClassification parseResponse(String body, LlmRuleContext ruleContext) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode classificationNode = root.has("classification") ? root.get("classification") : root;
        String level = classificationNode.path("riskLevel").asText(classificationNode.path("risk_level").asText());
        String rationale = classificationNode.path("rationale").asText();
        if (level == null || level.isBlank()) {
            return classifyByRules(ruleContext);
        }
        try {
            RiskLevel riskLevel = RiskLevel.valueOf(level.trim().toUpperCase());
            String resolvedRationale = rationale == null || rationale.isBlank()
                    ? "LLM classification applied."
                    : rationale.trim();
            return new LlmClassification(riskLevel, resolvedRationale);
        } catch (IllegalArgumentException error) {
            return classifyByRules(ruleContext);
        }
    }

    private LlmClassification classifyByRules(LlmRuleContext ruleContext) {
        if (ruleContext.blockedPattern() != null) {
            return new LlmClassification(RiskLevel.HIGH, "Blocked pattern matched.");
        }
        if (ruleContext.matchedKeyword() != null) {
            return new LlmClassification(RiskLevel.MEDIUM, "Keyword match detected.");
        }
        return new LlmClassification(RiskLevel.LOW, "No rules matched.");
    }
}
