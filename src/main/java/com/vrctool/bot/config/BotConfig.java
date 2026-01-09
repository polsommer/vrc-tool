package com.vrctool.bot.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public record BotConfig(
        String discordToken,
        String guildId,
        String welcomeChannelId,
        String modLogChannelId,
        String staffRoleId,
        String eventPingRoleId,
        String rulesLink,
        String groupLink,
        String supportLink,
        List<String> scanChannelIds,
        List<String> scanKeywords,
        Duration scanInterval
) {
    public static BotConfig fromEnvironment() {
        String token = getRequiredEnv("DISCORD_TOKEN");
        return new BotConfig(
                token,
                System.getenv("GUILD_ID"),
                System.getenv("WELCOME_CHANNEL_ID"),
                System.getenv("MOD_LOG_CHANNEL_ID"),
                System.getenv("STAFF_ROLE_ID"),
                System.getenv("EVENT_PING_ROLE_ID"),
                System.getenv("RULES_LINK"),
                System.getenv("GROUP_LINK"),
                System.getenv("SUPPORT_LINK"),
                parseList(System.getenv("MOD_SCAN_CHANNEL_IDS")),
                parseListOrDefault(System.getenv("MOD_SCAN_KEYWORDS"), defaultKeywords()),
                parseDurationSeconds(System.getenv("MOD_SCAN_INTERVAL_SECONDS"), 5)
        );
    }

    private static String getRequiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must be set in the environment");
        }
        return value;
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<String> parseListOrDefault(String value, List<String> fallback) {
        List<String> parsed = parseList(value);
        return parsed.isEmpty() ? fallback : parsed;
    }

    private static Duration parseDurationSeconds(String value, int defaultSeconds) {
        int seconds = defaultSeconds;
        if (value != null && !value.isBlank()) {
            try {
                seconds = Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                seconds = defaultSeconds;
            }
        }
        return Duration.ofSeconds(Math.max(5, seconds));
    }

    private static List<String> defaultKeywords() {
        return List.of(
                "harass",
                "threat",
                "dox",
                "swat",
                "leak",
                "hate",
                "slur",
                "exploit",
                "crash"
        );
    }
}
