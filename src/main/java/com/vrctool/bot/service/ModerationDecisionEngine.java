package com.vrctool.bot.service;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.util.ModerationPatterns;
import com.vrctool.bot.util.TextNormalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

public class ModerationDecisionEngine {
    private record KeywordPattern(String keyword, Pattern pattern) {}

    public enum Action {
        ALLOW,
        WARN,
        DELETE,
        ESCALATE_TO_MODS
    }

    public record DecisionContext(
            String content,
            String matchedKeyword,
            String blockedPattern,
            LlmClient.RiskLevel llmRiskLevel,
            String llmRationale,
            String selfReviewNote,
            int recentKeywordMatches,
            int totalRecentTokens,
            int channelRiskScore,
            int messageRiskScore,
            int historyRiskScore,
            int baseRiskScore,
            int llmScoreFloor,
            int totalRiskScore,
            int messageLength,
            int linkCount,
            double uppercaseRatio,
            int warnThreshold,
            int deleteThreshold,
            int escalateThreshold
    ) {}

    public record Decision(Action action, DecisionContext context) {}

    private record ReviewResult(Action action, String note) {}

    private static final Pattern LINK_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALLOWED_GIF_LINK_PATTERN = Pattern.compile(
            "https?://(?:www\\.)?tenor\\.com/view/\\S*gif\\S*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MINOR_REFERENCE_PATTERN = Pattern.compile(
            "\\b(minor|underage|child|kid|teen|13|14|15|16|17)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADULT_REFERENCE_PATTERN = Pattern.compile(
            "\\b(adult|18\\+|18\\s*plus|over\\s*18|18\\s*\\+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RELATIONSHIP_CONTEXT_PATTERN = Pattern.compile(
            "\\b(cuddle|cuddling|dating|relationship|boyfriend|girlfriend|bf|gf|romantic|flirt|"
                    + "kiss|sexual|dm|dms|messages|screenshots|evidence|proof|gifting|gifted)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern REPORT_CONTEXT_PATTERN = Pattern.compile(
            "\\b(report|reported|reporting|screenshots|evidence|proof|log|logs)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final BotConfig config;
    private final WordMemoryStore wordMemoryStore;
    private final List<Pattern> blockedPatterns;
    private final List<KeywordPattern> keywordPatterns;
    private final TextNormalizer textNormalizer;
    private final LlmClient llmClient;

    public ModerationDecisionEngine(
            BotConfig config,
            WordMemoryStore wordMemoryStore,
            TextNormalizer textNormalizer,
            LlmClient llmClient
    ) {
        this.config = config;
        this.wordMemoryStore = wordMemoryStore;
        this.textNormalizer = textNormalizer;
        this.llmClient = llmClient;
        this.blockedPatterns = config.blockedPatterns();
        this.keywordPatterns = config.scanKeywords().stream()
                .map(keyword -> new KeywordPattern(keyword, ModerationPatterns.compileKeywordPattern(keyword)))
                .toList();
    }

    public Decision evaluate(Message message, Member member, MessageChannel channel) {
        String content = message.getContentDisplay();
        String lowercase = content.toLowerCase(Locale.ROOT);
        TextNormalizer.NormalizedResult normalizedResult = textNormalizer.normalizeAndExpand(content);
        String normalized = normalizedResult.normalized();
        String expanded = normalizedResult.expanded();
        String sanitizedContent = stripAllowedGifLinks(content);
        TextNormalizer.NormalizedResult blockedNormalizedResult = textNormalizer.normalizeAndExpand(sanitizedContent);
        String blockedNormalized = blockedNormalizedResult.normalized();
        String blockedExpanded = blockedNormalizedResult.expanded();

        String matchedKeyword = null;
        for (KeywordPattern keywordPattern : keywordPatterns) {
            if (matchesAny(keywordPattern.pattern(), content, normalized, expanded)) {
                matchedKeyword = keywordPattern.keyword();
                break;
            }
        }
        if (matchedKeyword == null && isAgeGapConcern(content, normalized, expanded)) {
            matchedKeyword = "age gap (adult/minor)";
        }

        String blockedPattern = null;
        for (Pattern pattern : blockedPatterns) {
            if (matchesAny(pattern, sanitizedContent, blockedNormalized, blockedExpanded)) {
                blockedPattern = pattern.pattern();
                break;
            }
        }

        int messageLength = content.length();
        int linkCount = countLinks(sanitizedContent.toLowerCase(Locale.ROOT));
        double uppercaseRatio = calculateUppercaseRatio(content);
        int messageRiskScore = scoreMessageFormat(messageLength, linkCount, uppercaseRatio);

        Map<String, Integer> tokenCounts = wordMemoryStore.getTokenCounts(
                message.getGuild().getId(),
                channel.getId(),
                member.getId()
        );
        int totalRecentTokens = tokenCounts.values().stream().mapToInt(Integer::intValue).sum();
        int recentKeywordMatches = matchedKeyword == null
                ? 0
                : wordMemoryStore.getTokenCount(
                        message.getGuild().getId(),
                        channel.getId(),
                        member.getId(),
                        matchedKeyword
                );
        int historyRiskScore = scoreHistory(totalRecentTokens, recentKeywordMatches);
        int channelRiskScore = config.channelRiskScore(channel.getId());

        int baseRiskScore = 0;
        if (blockedPattern != null) {
            baseRiskScore += 70;
        }
        if (matchedKeyword != null) {
            baseRiskScore += 30;
        }

        LlmClient.LlmClassification llmClassification = llmClient.classifyMessage(
                content,
                new LlmClient.LlmRuleContext(matchedKeyword, blockedPattern)
        );
        int llmScoreFloor = switch (llmClassification.riskLevel()) {
            case HIGH -> config.modEscalateThreshold();
            case MEDIUM -> config.modDeleteThreshold();
            case LOW -> 0;
        };

        int totalRiskScore = baseRiskScore + messageRiskScore + historyRiskScore + channelRiskScore;
        totalRiskScore = Math.max(totalRiskScore, llmScoreFloor);

        Action action;
        if (totalRiskScore >= config.modEscalateThreshold()) {
            action = Action.ESCALATE_TO_MODS;
        } else if (totalRiskScore >= config.modDeleteThreshold()) {
            action = Action.DELETE;
        } else if (totalRiskScore >= config.modWarnThreshold()) {
            action = Action.WARN;
        } else {
            action = Action.ALLOW;
        }

        Action adjusted = quickThinkReview(
                action,
                matchedKeyword,
                blockedPattern,
                llmClassification.riskLevel(),
                messageRiskScore,
                historyRiskScore,
                channelRiskScore,
                content,
                normalized,
                expanded
        );

        ReviewResult review = reviewAction(
                adjusted,
                matchedKeyword,
                blockedPattern,
                llmClassification.riskLevel(),
                messageRiskScore,
                historyRiskScore,
                channelRiskScore
        );

        DecisionContext context = new DecisionContext(
                content,
                matchedKeyword,
                blockedPattern,
                llmClassification.riskLevel(),
                llmClassification.rationale(),
                review.note(),
                recentKeywordMatches,
                totalRecentTokens,
                channelRiskScore,
                messageRiskScore,
                historyRiskScore,
                baseRiskScore,
                llmScoreFloor,
                totalRiskScore,
                messageLength,
                linkCount,
                uppercaseRatio,
                config.modWarnThreshold(),
                config.modDeleteThreshold(),
                config.modEscalateThreshold()
        );

        return new Decision(review.action(), context);
    }

    private static boolean matchesAny(Pattern pattern, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && pattern.matcher(candidate).find()) {
                return true;
            }
        }
        return false;
    }

    private static int scoreMessageFormat(int messageLength, int linkCount, double uppercaseRatio) {
        int score = 0;
        if (messageLength >= 800) {
            score += 20;
        } else if (messageLength >= 400) {
            score += 10;
        }

        if (linkCount >= 2) {
            score += 12;
        } else if (linkCount == 1) {
            score += 6;
        }

        if (uppercaseRatio >= 0.7) {
            score += 8;
        }
        return score;
    }

    private static int scoreHistory(int totalRecentTokens, int recentKeywordMatches) {
        int score = 0;
        if (totalRecentTokens >= 2000) {
            score += 12;
        } else if (totalRecentTokens >= 800) {
            score += 6;
        }

        if (recentKeywordMatches > 0) {
            score += Math.min(recentKeywordMatches * 5, 25);
        }
        return score;
    }

    private static int countLinks(String content) {
        Matcher matcher = LINK_PATTERN.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static boolean isAgeGapConcern(String content, String normalized, String expanded) {
        return matchesAny(MINOR_REFERENCE_PATTERN, content, normalized, expanded)
                && matchesAny(ADULT_REFERENCE_PATTERN, content, normalized, expanded)
                && matchesAny(RELATIONSHIP_CONTEXT_PATTERN, content, normalized, expanded);
    }

    private static boolean isReportContext(String content, String normalized, String expanded) {
        return matchesAny(REPORT_CONTEXT_PATTERN, content, normalized, expanded);
    }

    private static String stripAllowedGifLinks(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return ALLOWED_GIF_LINK_PATTERN.matcher(content).replaceAll(" ");
    }

    private static double calculateUppercaseRatio(String content) {
        int uppercase = 0;
        int letters = 0;
        for (char c : content.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    uppercase++;
                }
            }
        }
        if (letters < 12) {
            return 0.0;
        }
        return letters == 0 ? 0.0 : (double) uppercase / letters;
    }

    private static ReviewResult reviewAction(
            Action proposed,
            String matchedKeyword,
            String blockedPattern,
            LlmClient.RiskLevel llmRiskLevel,
            int messageRiskScore,
            int historyRiskScore,
            int channelRiskScore
    ) {
        if (proposed == Action.ALLOW) {
            return new ReviewResult(Action.ALLOW, "No moderation action required.");
        }
        if (blockedPattern != null || matchedKeyword != null) {
            return new ReviewResult(proposed, "Rule match present; keep action.");
        }
        if (llmRiskLevel != LlmClient.RiskLevel.LOW) {
            return new ReviewResult(proposed, "LLM risk elevated; keep action.");
        }
        if (historyRiskScore > 0) {
            return new ReviewResult(proposed, "Recent history indicates spam; keep action.");
        }
        if (channelRiskScore > 0) {
            return new ReviewResult(proposed, "Channel risk profile elevated; keep action.");
        }
        if (messageRiskScore >= 12) {
            return new ReviewResult(proposed, "Message formatting indicates spam; keep action.");
        }
        return new ReviewResult(Action.ALLOW, "Low risk with no rule matches; action downgraded.");
    }

    private static Action quickThinkReview(
            Action proposed,
            String matchedKeyword,
            String blockedPattern,
            LlmClient.RiskLevel llmRiskLevel,
            int messageRiskScore,
            int historyRiskScore,
            int channelRiskScore,
            String content,
            String normalized,
            String expanded
    ) {
        if (proposed != Action.DELETE) {
            return proposed;
        }
        if (blockedPattern != null) {
            return proposed;
        }
        if (llmRiskLevel == LlmClient.RiskLevel.HIGH) {
            return proposed;
        }
        boolean reportContext = isReportContext(content, normalized, expanded);
        boolean softSignals = messageRiskScore < 10 && historyRiskScore < 5 && channelRiskScore == 0;
        if (reportContext || softSignals) {
            return Action.WARN;
        }
        if (matchedKeyword != null && matchedKeyword.contains("age gap")) {
            return Action.ESCALATE_TO_MODS;
        }
        return proposed;
    }

}
