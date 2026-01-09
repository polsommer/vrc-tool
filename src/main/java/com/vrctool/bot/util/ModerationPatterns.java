package com.vrctool.bot.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ModerationPatterns {
    private static final String NON_ALNUM = "[^\\p{Alnum}]*";

    private ModerationPatterns() {
    }

    public static Pattern compileKeywordPattern(String term) {
        return compileKeywordPatternInternal(term, true);
    }

    public static Pattern compileBlockedPattern(String term) {
        String trimmed = term == null ? "" : term.trim();
        if (trimmed.isEmpty()) {
            return Pattern.compile("$a");
        }
        StringBuilder regex = new StringBuilder();
        regex.append("(?<!\\p{Alnum})");
        boolean pendingSpace = false;
        for (char rawChar : trimmed.toCharArray()) {
            if (Character.isWhitespace(rawChar)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace) {
                regex.append("\\s+");
                pendingSpace = false;
            }
            String obfuscated = obfuscationPattern(rawChar);
            if (obfuscated != null) {
                regex.append(obfuscated);
            } else {
                regex.append(Pattern.quote(String.valueOf(rawChar)));
            }
        }
        regex.append("(?!\\p{Alnum})");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static Pattern compileKeywordPatternInternal(String term, boolean allowSuffix) {
        String trimmed = term == null ? "" : term.trim();
        if (trimmed.isEmpty()) {
            return Pattern.compile("$a");
        }
        List<Character> characters = new ArrayList<>();
        for (char rawChar : trimmed.toCharArray()) {
            if (!Character.isWhitespace(rawChar)) {
                characters.add(rawChar);
            }
        }
        if (characters.isEmpty()) {
            return Pattern.compile("$a");
        }
        StringBuilder regex = new StringBuilder();
        regex.append("(?<!\\p{Alnum})");
        for (int index = 0; index < characters.size(); index++) {
            char rawChar = characters.get(index);
            String obfuscated = obfuscationPattern(rawChar);
            if (obfuscated != null) {
                regex.append(obfuscated);
            } else {
                regex.append(Pattern.quote(String.valueOf(rawChar)));
            }
            if (index < characters.size() - 1) {
                regex.append(NON_ALNUM);
            }
        }
        if (allowSuffix) {
            regex.append("(?:").append(NON_ALNUM).append("\\p{Alnum}+)?");
        }
        regex.append("(?!\\p{Alnum})");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static String obfuscationPattern(char value) {
        return switch (Character.toLowerCase(value)) {
            case 'a' -> "[a@]";
            case 'e' -> "[e3]";
            case 'i' -> "[i1!]";
            case 'o' -> "[o0]";
            case 's' -> "[s5$]";
            case 't' -> "[t7]";
            default -> null;
        };
    }
}
