package com.vrctool.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class FaqService {
    private final List<FaqEntry> entries;

    public FaqService(String resourcePath) {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("FAQ resource not found: " + resourcePath);
            }
            entries = mapper.readValue(stream, new TypeReference<>() {});
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load FAQ entries", ex);
        }
    }

    public List<FaqEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public Optional<FaqEntry> findByTopic(String topic) {
        if (topic == null) {
            return Optional.empty();
        }
        String normalized = topic.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(entry -> entry.topic().equalsIgnoreCase(normalized))
                .findFirst();
    }

    public Optional<FaqEntry> findBestMatch(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        return entries.stream()
                .map(entry -> new ScoredEntry(entry, scoreEntry(entry, query)))
                .filter(scored -> scored.score() > 0)
                .max(Comparator.comparingDouble(ScoredEntry::score))
                .map(ScoredEntry::entry);
    }

    public List<String> suggestTopics(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> new ScoredEntry(entry, scoreEntry(entry, query)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .limit(limit)
                .map(scored -> scored.entry().topic())
                .toList();
    }

    private double scoreEntry(FaqEntry entry, String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        if (entry.topic().equalsIgnoreCase(normalizedQuery)) {
            return 1.5d;
        }
        Set<String> queryTokens = tokenize(normalizedQuery);
        if (queryTokens.isEmpty()) {
            return 0d;
        }
        Set<String> entryTokens = tokenize(entry.topic() + " " + entry.title() + " " + entry.description());
        long matches = queryTokens.stream().filter(entryTokens::contains).count();
        return (double) matches / queryTokens.size();
    }

    private Set<String> tokenize(String input) {
        return Arrays.stream(input.split("[^a-z0-9]+"))
                .map(String::trim)
                .filter(token -> token.length() > 2)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private record ScoredEntry(FaqEntry entry, double score) {}
}
