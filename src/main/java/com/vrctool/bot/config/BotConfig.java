package com.vrctool.bot.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public record BotConfig(
        String discordToken,
        String guildId,
        String welcomeChannelId,
        String modLogChannelId,
        String activePlayersChannelId,
        String staffRoleId,
        String eventPingRoleId,
        String rulesLink,
        String groupLink,
        String supportLink,
        List<String> scanChannelIds,
        List<String> scanKeywords,
        List<Pattern> blockedPatterns,
        Duration scanInterval,
        int activePlayersWebPort,
        String activePlayersWebToken
) {
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("[A-Z0-9_]+");
    private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();
    private static final String REQUIRED_SCAN_CHANNEL_ID = "1350853422064336969";
    private static final String DEFAULT_ACTIVE_PLAYERS_CHANNEL_ID = "1459232504711217213";

    public static BotConfig fromEnvironment() {
        String token = getRequiredEnv("DISCORD_TOKEN");

        return new BotConfig(
                token,
                getOptionalEnv("GUILD_ID"),
                getOptionalEnv("WELCOME_CHANNEL_ID"),
                getOptionalEnv("MOD_LOG_CHANNEL_ID"),
                resolveChannelId(
                        getOptionalEnv("ACTIVE_PLAYERS_CHANNEL_ID"),
                        getOptionalEnv("MOD_LOG_CHANNEL_ID"),
                        DEFAULT_ACTIVE_PLAYERS_CHANNEL_ID
                ),
                getOptionalEnv("STAFF_ROLE_ID"),
                getOptionalEnv("EVENT_PING_ROLE_ID"),
                getOptionalEnv("RULES_LINK"),
                getOptionalEnv("GROUP_LINK"),
                getOptionalEnv("SUPPORT_LINK"),
                parseScanChannelIds(getOptionalEnv("MOD_SCAN_CHANNEL_IDS")),

                // Keywords (String-based)
                parseListOrDefault(
                        getOptionalEnv("MOD_SCAN_KEYWORDS"),
                        defaultKeywords()
                ),

                // Regex patterns (Pattern-based)
                parsePatternsOrDefault(
                        getOptionalEnv("MOD_BLOCKED_PATTERNS"),
                        defaultBlockedPatterns()
                ),

                parseDurationSeconds(
                        getOptionalEnv("MOD_SCAN_INTERVAL_SECONDS"),
                        5
                ),
                parsePort(
                        getOptionalEnv("ACTIVE_PLAYERS_WEB_PORT"),
                        8123
                ),
                getOptionalEnv("ACTIVE_PLAYERS_WEB_TOKEN")
        );
    }
    private static List<Pattern> parsePatternsOrDefault(
            String env,
            List<Pattern> fallback
    ) {
        if (env == null || env.isBlank()) {
            return fallback;
        }

        List<Pattern> patterns = new ArrayList<>();

        for (String raw : env.split(",")) {
            String regex = raw.trim();
            if (regex.isEmpty()) continue;

            try {
                patterns.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                // Log but do NOT crash the bot
                System.err.println(
                        "[MODERATION] Invalid regex ignored: " + regex
                );
            }
        }

        return patterns.isEmpty() ? fallback : patterns;
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

    private static String resolveChannelId(String primary, String fallback, String defaultValue) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return (defaultValue != null && !defaultValue.isBlank()) ? defaultValue : null;
    }

    private static int parsePort(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            return defaultPort;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= 65535) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            return defaultPort;
        }
        return defaultPort;
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

                // ===============================
                // HARASSMENT & THREATS
                // ===============================
                "harass", "harassment",
                "threat", "threaten", "threatening",
                "intimidate", "intimidation",
                "bully", "bullying",
                "abuse", "abusive",
                "stalk", "stalking",
                "blackmail", "extort", "extortion",
                "coerce", "coercion",

                // ===============================
                // DOXXING & PRIVACY VIOLATIONS
                // ===============================
                "dox", "doxx", "doxxed", "doxxing",
                "leak", "leaks", "leaked",
                "expose", "exposed",
                "ip", "ip address", "ipv4", "ipv6",
                "home address", "house address",
                "real address",
                "phone number", "phone #", "mobile number",
                "email address", "private info",
                "personal info", "personal information",
                "ssn", "social security",
                "passport", "driver license",
                "credit card", "debit card",

                // ===============================
                // HATE SPEECH & EXTREMISM
                // ===============================
                "hate", "hateful",
                "slur", "slurs",
                "racist", "racism",
                "bigot", "bigotry",
                "nazi", "neo nazi",
                "fascist", "white power",
                "kkk",
                "genocide", "ethnic cleansing",
                "supremacy", "hate crime",

                // ===============================
                // SELF-HARM & SUICIDE BAITING
                // ===============================
                "kys",
                "kill yourself",
                "kill urself",
                "go kill yourself",
                "go die",
                "you should die",
                "end your life",
                "unalive yourself",
                "suicide bait",
                "self harm",
                "self-harm",
                "commit suicide",

                // ===============================
                // SEXUAL ABUSE & EXPLOITATION
                // ===============================
                "rape", "raped", "rapist",
                "sexual assault", "sexual abuse",
                "molest", "molestation",
                "pedo", "pedophile", "pedophilia",
                "groom", "groomer", "grooming",
                "child porn", "child pornography",
                "cp",
                "minor sexual",
                "underage sex",

                // ===============================
                // VIOLENCE & PHYSICAL HARM
                // ===============================
                "kill", "murder", "execute",
                "beat", "assault",
                "shoot", "shooting",
                "stab", "stabbing",
                "bomb", "bombing",
                "terrorist", "terrorism",
                "massacre",
                "death threat",

                // ===============================
                // CYBERCRIME & ATTACKS
                // ===============================
                "ddos", "dos attack",
                "crash server", "server crash",
                "hack", "hacking",
                "exploit", "exploiting",
                "breach", "data breach",
                "malware", "virus", "trojan",
                "rat", "keylogger",
                "phishing", "scam", "fraud",

                // ===============================
                // SCAMS & SOCIAL ENGINEERING
                // ===============================
                "free nitro",
                "free discord nitro",
                "steam gift",
                "steam giveaway",
                "crypto scam",
                "investment scam",
                "fake giveaway",
                "airdrop scam",
                "impersonation",
                "account recovery scam",

                // ===============================
                // SPAM / MALICIOUS BEHAVIOR
                // ===============================
                "join my server",
                "click this link",
                "limited time offer",
                "act now",
                "dm me for info",
                "dm for details",
                "too good to be true",

                // ===============================
                // ILLEGAL CONTENT
                // ===============================
                "illegal drugs",
                "sell drugs",
                "buy drugs",
                "cocaine",
                "heroin",
                "meth",
                "fentanyl",
                "weapons sale",
                "gun for sale",
                "unregistered weapon"
        );
    }

    private static List<Pattern> defaultBlockedPatterns() {
        return List.of(

                // =====================================
                // LINKS & URL OBFUSCATION
                // =====================================
                Pattern.compile("http[s]?://", Pattern.CASE_INSENSITIVE),
                Pattern.compile("www\\.", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\b[a-z0-9.-]+\\.(com|net|org|io|ru|cn|tk|xyz|top|gg)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("h\\s*t\\s*t\\s*p", Pattern.CASE_INSENSITIVE), // h t t p evasion
                Pattern.compile("dot\\s*(com|net|org|gg)", Pattern.CASE_INSENSITIVE),

                // =====================================
                // DISCORD / PLATFORM INVITES
                // =====================================
                Pattern.compile("discord(\\.|\\s)*(gg|com)(/|\\s)*(invite)?", Pattern.CASE_INSENSITIVE),
                Pattern.compile("d\\s*i\\s*s\\s*c\\s*o\\s*r\\s*d", Pattern.CASE_INSENSITIVE),
                Pattern.compile("join\\s+my\\s+(discord|server)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("invite\\s+link", Pattern.CASE_INSENSITIVE),

                // =====================================
                // SCAMS & GIVEAWAYS
                // =====================================
                Pattern.compile("free\\s*nitro", Pattern.CASE_INSENSITIVE),
                Pattern.compile("nitro\\s*generator", Pattern.CASE_INSENSITIVE),
                Pattern.compile("steam\\s*(gift|giveaway|code)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("crypto\\s*(giveaway|airdrop)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("wallet\\s*connect", Pattern.CASE_INSENSITIVE),
                Pattern.compile("double\\s*your\\s*(crypto|btc|eth)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("investment\\s*guaranteed", Pattern.CASE_INSENSITIVE),
                Pattern.compile("risk\\s*free\\s*profit", Pattern.CASE_INSENSITIVE),

                // =====================================
                // PHISHING / TOKEN GRABBERS
                // =====================================
                Pattern.compile("verify\\s+your\\s+account", Pattern.CASE_INSENSITIVE),
                Pattern.compile("account\\s+disabled", Pattern.CASE_INSENSITIVE),
                Pattern.compile("suspicious\\s+activity", Pattern.CASE_INSENSITIVE),
                Pattern.compile("login\\s+to\\s+continue", Pattern.CASE_INSENSITIVE),
                Pattern.compile("reset\\s+your\\s+password", Pattern.CASE_INSENSITIVE),
                Pattern.compile("token\\s*grab", Pattern.CASE_INSENSITIVE),
                Pattern.compile("grab\\s*token", Pattern.CASE_INSENSITIVE),

                // =====================================
                // MALWARE / EXPLOITS
                // =====================================
                Pattern.compile("exe\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("jar\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("bat\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("powershell", Pattern.CASE_INSENSITIVE),
                Pattern.compile("cmd\\.exe", Pattern.CASE_INSENSITIVE),
                Pattern.compile("keylogger", Pattern.CASE_INSENSITIVE),
                Pattern.compile("rat\\s*tool", Pattern.CASE_INSENSITIVE),
                Pattern.compile("remote\\s*access\\s*tool", Pattern.CASE_INSENSITIVE),

                // =====================================
                // DOXXING / DATA LEAK FORMATS
                // =====================================
                Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b"), // IPv4
                Pattern.compile("\\b[0-9a-f:]{2,}\\b", Pattern.CASE_INSENSITIVE), // IPv6-ish
                Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), // SSN
                Pattern.compile("\\b\\d{16}\\b"), // credit card-like
                Pattern.compile("\\b\\d{10,15}\\b"), // phone numbers

                // =====================================
                // EVASION & OBFUSCATION
                // =====================================
                Pattern.compile("(.)\\1{6,}"), // aaaaaaa spam
                Pattern.compile("(\\s*[a-z]\\s*){6,}", Pattern.CASE_INSENSITIVE), // spaced letters
                Pattern.compile("[a-zA-Z0-9]{30,}"), // base64 / tokens
                Pattern.compile("zero\\s*width", Pattern.CASE_INSENSITIVE),

                // =====================================
                // RAID / SPAM BEHAVIOR
                // =====================================
                Pattern.compile("everyone\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("here\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("raid\\s*(this|now)", Pattern.CASE_INSENSITIVE),
                Pattern.compile("spam\\s*(this|chat)", Pattern.CASE_INSENSITIVE),

                // =====================================
                // SOCIAL ENGINEERING
                // =====================================
                Pattern.compile("dm\\s*me", Pattern.CASE_INSENSITIVE),
                Pattern.compile("private\\s*message\\s*me", Pattern.CASE_INSENSITIVE),
                Pattern.compile("add\\s*me\\s*back", Pattern.CASE_INSENSITIVE),
                Pattern.compile("trust\\s*me", Pattern.CASE_INSENSITIVE),

                // =====================================
                // FILE SHARING / MIRRORS
                // =====================================
                Pattern.compile("mega\\.nz", Pattern.CASE_INSENSITIVE),
                Pattern.compile("mediafire", Pattern.CASE_INSENSITIVE),
                Pattern.compile("dropbox", Pattern.CASE_INSENSITIVE),
                Pattern.compile("pastebin", Pattern.CASE_INSENSITIVE),
                Pattern.compile("anonfiles", Pattern.CASE_INSENSITIVE),

                // =====================================
                // CLEAR BYPASS ATTEMPTS
                // =====================================
                Pattern.compile("bypass", Pattern.CASE_INSENSITIVE),
                Pattern.compile("filter\\s*evasion", Pattern.CASE_INSENSITIVE),
                Pattern.compile("anti\\s*ban", Pattern.CASE_INSENSITIVE),
                Pattern.compile("undetectable", Pattern.CASE_INSENSITIVE)
        );
    }
}
