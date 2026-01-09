package com.vrctool.bot.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
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
        List<String> blockedPatterns,
        Duration scanInterval
) {
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("[A-Z0-9_]+");
    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();
    private static final String REQUIRED_SCAN_CHANNEL_ID = "1350853422064336969";

    public static BotConfig fromEnvironment() {
        String token = getRequiredEnv("DISCORD_TOKEN");
        return new BotConfig(
                token,
                getOptionalEnv("GUILD_ID"),
                getOptionalEnv("WELCOME_CHANNEL_ID"),
                getOptionalEnv("MOD_LOG_CHANNEL_ID"),
                getOptionalEnv("STAFF_ROLE_ID"),
                getOptionalEnv("EVENT_PING_ROLE_ID"),
                getOptionalEnv("RULES_LINK"),
                getOptionalEnv("GROUP_LINK"),
                getOptionalEnv("SUPPORT_LINK"),
                parseScanChannelIds(getOptionalEnv("MOD_SCAN_CHANNEL_IDS")),
                parseListOrDefault(getOptionalEnv("MOD_SCAN_KEYWORDS"), defaultKeywords()),
                parseListOrDefault(getOptionalEnv("MOD_BLOCKED_PATTERNS"), defaultBlockedPatterns()),
                parseDurationSeconds(getOptionalEnv("MOD_SCAN_INTERVAL_SECONDS"), 5)
        );
    }

    private static String getRequiredEnv(String key) {
        String value = getEnv(key);
        if (value == null || value.isBlank()) {
            String safeKey = ENV_KEY_PATTERN.matcher(key).matches()
                    ? key
                    : "required environment variable";
            throw new IllegalStateException(safeKey + " must be set in the environment");
        }
        return value;
    }

    private static String getOptionalEnv(String key) {
        return getEnv(key);
    }

    private static String getEnv(String key) {
        String value = DOTENV.get(key);
        if (value == null || value.isBlank()) {
            return System.getenv(key);
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

    private static List<String> parseScanChannelIds(String value) {
        return Arrays.stream(
                        (value == null || value.isBlank())
                                ? new String[] {REQUIRED_SCAN_CHANNEL_ID}
                                : (value + "," + REQUIRED_SCAN_CHANNEL_ID).split(",")
                )
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .distinct()
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

    private static List<String> defaultBlockedPatterns() {
        return List.of(
                "discord.gg",
                "discord.com/invite",
                "free nitro",
                "steamgift"
        );
    }
}
