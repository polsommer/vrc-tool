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
            int recentKeywordMatches,
            int totalRecentTokens,
            int channelRiskScore,
            int messageRiskScore,
            int historyRiskScore,
            int baseRiskScore,
            int totalRiskScore,
            int messageLength,
            int linkCount,
            double uppercaseRatio,
            int warnThreshold,
            int deleteThreshold,
            int escalateThreshold
    ) {}

    public record Decision(Action action, DecisionContext context) {}

    private static final Pattern LINK_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final BotConfig config;
    private final WordMemoryStore wordMemoryStore;
    private final List<Pattern> blockedPatterns;
    private final List<KeywordPattern> keywordPatterns;
    private final TextNormalizer textNormalizer;

    public ModerationDecisionEngine(BotConfig config, WordMemoryStore wordMemoryStore, TextNormalizer textNormalizer) {
        this.config = config;
        this.wordMemoryStore = wordMemoryStore;
        this.textNormalizer = textNormalizer;
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

        String matchedKeyword = null;
        for (KeywordPattern keywordPattern : keywordPatterns) {
            if (matchesAny(keywordPattern.pattern(), content, normalized, expanded)) {
                matchedKeyword = keywordPattern.keyword();
                break;
            }
        }

        String blockedPattern = null;
        for (Pattern pattern : blockedPatterns) {
            if (matchesAny(pattern, content, normalized, expanded)) {
                blockedPattern = pattern.pattern();
                break;
            }
        }

        int messageLength = content.length();
        int linkCount = countLinks(lowercase);
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

        int totalRiskScore = baseRiskScore + messageRiskScore + historyRiskScore + channelRiskScore;

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

        DecisionContext context = new DecisionContext(
                content,
                matchedKeyword,
                blockedPattern,
                recentKeywordMatches,
                totalRecentTokens,
                channelRiskScore,
                messageRiskScore,
                historyRiskScore,
                baseRiskScore,
                totalRiskScore,
                messageLength,
                linkCount,
                uppercaseRatio,
                config.modWarnThreshold(),
                config.modDeleteThreshold(),
                config.modEscalateThreshold()
        );

        return new Decision(action, context);
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
}
