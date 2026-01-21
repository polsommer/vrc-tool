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
            LlmClassification classification = new LlmClassification(RiskLevel.LOW, "LLM classification disabled.");
            logDecision("disabled", content, ruleContext, classification, "LLM classification disabled in config.");
            return classification;
        }
        String endpoint = config.llmEndpointUrl();
        if (endpoint == null || endpoint.isBlank()) {
            LlmClassification classification = classifyByRules(ruleContext);
            logDecision("rules", content, ruleContext, classification, "LLM endpoint not configured.");
            return classification;
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
                LlmClassification classification = classifyByRules(ruleContext);
                logDecision(
                        "rules",
                        content,
                        ruleContext,
                        classification,
                        "LLM HTTP status " + response.statusCode() + "; using rules."
                );
                return classification;
            }
            LlmDecision decision = parseResponse(response.body(), ruleContext);
            logDecision(decision.source(), content, ruleContext, decision.classification(), decision.note());
            return decision.classification();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LlmClassification classification = classifyByRules(ruleContext);
            logDecision("rules", content, ruleContext, classification, "LLM request interrupted; using rules.");
            return classification;
        } catch (IOException | IllegalArgumentException error) {
            LlmClassification classification = classifyByRules(ruleContext);
            logDecision("rules", content, ruleContext, classification, "LLM request failed; using rules.");
            return classification;
        }
    }

    private String buildRequestPayload(String content) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", content);
        payload.put("format", "risk");
        payload.put("response_format", "json");
        return objectMapper.writeValueAsString(payload);
    }

    private LlmDecision parseResponse(String body, LlmRuleContext ruleContext) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        JsonNode classificationNode = root.has("classification") ? root.get("classification") : root;
        String level = classificationNode.path("riskLevel").asText(classificationNode.path("risk_level").asText());
        String rationale = classificationNode.path("rationale").asText();
        if (level == null || level.isBlank()) {
            LlmClassification classification = classifyByRules(ruleContext);
            return new LlmDecision(classification, "rules", "LLM response missing risk level; using rules.");
        }
        try {
            RiskLevel riskLevel = RiskLevel.valueOf(level.trim().toUpperCase());
            String resolvedRationale = rationale == null || rationale.isBlank()
                    ? "LLM classification applied."
                    : rationale.trim();
            return new LlmDecision(new LlmClassification(riskLevel, resolvedRationale), "llm", "LLM response parsed.");
        } catch (IllegalArgumentException error) {
            LlmClassification classification = classifyByRules(ruleContext);
            return new LlmDecision(classification, "rules", "LLM response had invalid risk level; using rules.");
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

    private void logDecision(
            String source,
            String content,
            LlmRuleContext ruleContext,
            LlmClassification classification,
            String note
    ) {
        if (!config.llmDebugEnabled()) {
            return;
        }
        String preview = summarizeContent(content);
        String matchedKeyword = safeValue(ruleContext.matchedKeyword());
        String blockedPattern = safeValue(ruleContext.blockedPattern());
        String resolvedNote = note == null || note.isBlank() ? "No additional notes." : note.trim();
        System.out.println(
                "[LLM_DEBUG] Professional reasoning summary | source=" + source
                        + " | risk=" + classification.riskLevel()
                        + " | rationale=\"" + classification.rationale() + "\""
                        + " | signals={blockedPattern=" + blockedPattern
                        + ", matchedKeyword=" + matchedKeyword + "}"
                        + " | messageLength=" + (content == null ? 0 : content.length())
                        + " | messagePreview=\"" + preview + "\""
                        + " | note=\"" + resolvedNote + "\""
        );
    }

    private String summarizeContent(String content) {
        if (content == null || content.isBlank()) {
            return "n/a";
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    private String safeValue(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.trim();
    }

    private record LlmDecision(LlmClassification classification, String source, String note) {}
}
