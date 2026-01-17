package com.vrctool.bot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class TextNormalizer {
    public enum MorphologyMode {
        NONE,
        STEM,
        LEMMA
    }

    public record NormalizedResult(String normalized, String expanded) {}

    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MorphologyMode morphologyMode;
    private final Map<String, Set<String>> synonymExpansions;

    public TextNormalizer(Map<String, List<String>> synonyms, MorphologyMode morphologyMode) {
        this.morphologyMode = Objects.requireNonNull(morphologyMode, "morphologyMode");
        this.synonymExpansions = buildExpansions(synonyms == null ? Map.of() : synonyms);
    }

    public static TextNormalizer fromResource(String resourcePath, MorphologyMode morphologyMode) {
        Map<String, List<String>> synonyms = loadSynonyms(resourcePath);
        return new TextNormalizer(synonyms, morphologyMode);
    }

    public NormalizedResult normalizeAndExpand(String input) {
        String normalized = normalize(input);
        String expanded = expandWithSynonyms(normalized);
        return new NormalizedResult(normalized, expanded);
    }

    public String normalize(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        String lowered = input.toLowerCase(Locale.ROOT);
        String cleaned = NON_ALNUM.matcher(lowered).replaceAll(" ");
        String collapsed = WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
        if (collapsed.isBlank()) {
            return "";
        }
        if (morphologyMode == MorphologyMode.NONE) {
            return collapsed;
        }
        String[] tokens = collapsed.split(" ");
        List<String> normalizedTokens = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String normalized = applyMorphology(token);
            if (!normalized.isBlank()) {
                normalizedTokens.add(normalized);
            }
        }
        return String.join(" ", normalizedTokens).trim();
    }

    public String expandWithSynonyms(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String[] tokens = normalized.split(" ");
        Set<String> expandedTokens = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            expandedTokens.add(token);
            Set<String> expansions = synonymExpansions.get(token);
            if (expansions != null) {
                expandedTokens.addAll(expansions);
            }
        }
        return String.join(" ", expandedTokens);
    }

    private Map<String, Set<String>> buildExpansions(Map<String, List<String>> synonyms) {
        if (synonyms.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> expansions = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : synonyms.entrySet()) {
            String key = normalizeToken(entry.getKey());
            if (key.isBlank()) {
                continue;
            }
            Set<String> group = new LinkedHashSet<>();
            group.add(key);
            List<String> values = entry.getValue();
            if (values != null) {
                for (String synonym : values) {
                    String normalized = normalizeToken(synonym);
                    if (!normalized.isBlank()) {
                        group.add(normalized);
                    }
                }
            }
            for (String term : group) {
                expansions.computeIfAbsent(term, ignored -> new LinkedHashSet<>()).addAll(group);
            }
        }
        for (Map.Entry<String, Set<String>> entry : expansions.entrySet()) {
            entry.getValue().remove(entry.getKey());
        }
        return Collections.unmodifiableMap(expansions);
    }

    private static Map<String, List<String>> loadSynonyms(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return Map.of();
        }
        String trimmed = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        try (InputStream inputStream = TextNormalizer.class.getClassLoader().getResourceAsStream(trimmed)) {
            if (inputStream == null) {
                System.err.println("[NORMALIZER] Synonyms resource not found: " + resourcePath);
                return Map.of();
            }
            return MAPPER.readValue(inputStream, new TypeReference<Map<String, List<String>>>() {});
        } catch (IOException e) {
            System.err.println("[NORMALIZER] Failed to read synonyms: " + e.getMessage());
            return Map.of();
        }
    }

    private String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String cleaned = NON_ALNUM.matcher(token.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        if (cleaned.isBlank()) {
            return "";
        }
        String collapsed = WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
        if (collapsed.contains(" ")) {
            return collapsed;
        }
        if (morphologyMode == MorphologyMode.NONE) {
            return collapsed;
        }
        return applyMorphology(collapsed);
    }

    private String applyMorphology(String token) {
        String normalized = token;
        if (morphologyMode == MorphologyMode.LEMMA) {
            String lemma = lemmatizeToken(normalized);
            if (!lemma.equals(normalized)) {
                return lemma;
            }
        }
        if (morphologyMode == MorphologyMode.STEM || morphologyMode == MorphologyMode.LEMMA) {
            return stemToken(normalized);
        }
        return normalized;
    }

    private static String lemmatizeToken(String token) {
        return switch (token) {
            case "children" -> "child";
            case "people" -> "person";
            case "men" -> "man";
            case "women" -> "woman";
            case "mice" -> "mouse";
            case "geese" -> "goose";
            default -> token;
        };
    }

    private static String stemToken(String token) {
        if (token.length() <= 3) {
            return token;
        }
        if (token.endsWith("ing") && token.length() > 5) {
            return token.substring(0, token.length() - 3);
        }
        if (token.endsWith("ed") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("es") && token.length() > 4) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && token.length() > 3) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }
}
