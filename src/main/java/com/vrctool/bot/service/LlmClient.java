package com.vrctool.bot.service;

public interface LlmClient {
    enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    record LlmClassification(RiskLevel riskLevel, String rationale) {}

    record LlmRuleContext(String matchedKeyword, String blockedPattern) {}

    LlmClassification classifyMessage(String content, LlmRuleContext ruleContext);
}
