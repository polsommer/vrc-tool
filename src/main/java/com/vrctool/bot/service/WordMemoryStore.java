package com.vrctool.bot.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class WordMemoryStore {
    private record MemoryKey(String guildId, String channelId, String userId) {}

    private record MemoryEvent(
            long timestampMillis,
            String guildId,
            String channelId,
            String userId,
            String content,
            Map<String, Integer> tokenCounts
    ) {}

    private record MemoryMessage(
            long timestampMillis,
            String content
    ) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    private final Path path;
    private final Duration retention;
    private final Deque<MemoryEvent> events;
    private final Map<MemoryKey, Map<String, Integer>> counts;
    private final Map<MemoryKey, Deque<MemoryMessage>> recentMessages;

    public WordMemoryStore(Path path) {
        this(path, DEFAULT_RETENTION);
    }

    public WordMemoryStore(Path path, Duration retention) {
        this.path = Objects.requireNonNull(path, "path");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.events = new ArrayDeque<>();
        this.counts = new HashMap<>();
        this.recentMessages = new HashMap<>();
    }

    public synchronized void load() {
        if (!Files.exists(path)) {
            return;
        }
        boolean compactNeeded = false;
        Instant now = Instant.now();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    MemoryEvent event = MAPPER.readValue(line, MemoryEvent.class);
                    if (isExpired(event, now)) {
                        compactNeeded = true;
                        continue;
                    }
                    addEvent(event);
                } catch (JsonProcessingException e) {
                    compactNeeded = true;
                    System.err.println("[WORD_MEMORY] Invalid JSONL entry skipped.");
                }
            }
        } catch (IOException e) {
            System.err.println("[WORD_MEMORY] Failed to read store: " + e.getMessage());
        }
        if (compactNeeded) {
            rewriteFile();
        }
    }

    public synchronized void recordMessage(
            String guildId,
            String channelId,
            String userId,
            String content,
            Instant timestamp
    ) {
        if (content == null || content.isBlank()) {
            return;
        }
        List<String> tokens = tokenizeContent(content);
        if (tokens.isEmpty()) {
            return;
        }
        Map<String, Integer> tokenCounts = buildTokenCounts(tokens);
        MemoryEvent event = new MemoryEvent(
                timestamp.toEpochMilli(),
                guildId,
                channelId,
                userId,
                content,
                tokenCounts
        );
        boolean compactNeeded = prune(Instant.now());
        addEvent(event);
        if (compactNeeded) {
            rewriteFile();
        } else {
            appendEvent(event);
        }
    }

    public synchronized List<String> getRecentMessages(
            String guildId,
            String channelId,
            String userId,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }
        MemoryKey key = new MemoryKey(guildId, channelId, userId);
        Deque<MemoryMessage> messages = recentMessages.get(key);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        pruneMessages(messages, Instant.now());
        if (messages.isEmpty()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        int count = 0;
        for (MemoryMessage message : messages.descendingIterator()) {
            if (message.content() != null && !message.content().isBlank()) {
                results.add(message.content());
                count++;
                if (count >= limit) {
                    break;
                }
            }
        }
        return results;
    }

    public synchronized int getTokenCount(
            String guildId,
            String channelId,
            String userId,
            String token
    ) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        String normalized = normalizeToken(token);
        MemoryKey key = new MemoryKey(guildId, channelId, userId);
        Map<String, Integer> tokenCounts = counts.get(key);
        if (tokenCounts == null) {
            return 0;
        }
        return tokenCounts.getOrDefault(normalized, 0);
    }

    public synchronized Map<String, Integer> getTokenCounts(
            String guildId,
            String channelId,
            String userId
    ) {
        MemoryKey key = new MemoryKey(guildId, channelId, userId);
        Map<String, Integer> tokenCounts = counts.get(key);
        if (tokenCounts == null) {
            return Map.of();
        }
        return Map.copyOf(tokenCounts);
    }

    private void addEvent(MemoryEvent event) {
        if (event == null) {
            return;
        }
        events.addLast(event);
        MemoryKey key = new MemoryKey(event.guildId(), event.channelId(), event.userId());
        Map<String, Integer> tokenCounts = counts.computeIfAbsent(key, ignored -> new HashMap<>());
        for (Map.Entry<String, Integer> entry : event.tokenCounts().entrySet()) {
            tokenCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
        if (event.content() != null && !event.content().isBlank()) {
            Deque<MemoryMessage> messages = recentMessages.computeIfAbsent(key, ignored -> new ArrayDeque<>());
            messages.addLast(new MemoryMessage(event.timestampMillis(), event.content()));
        }
    }

    private boolean prune(Instant now) {
        boolean removed = false;
        while (!events.isEmpty() && isExpired(events.peekFirst(), now)) {
            MemoryEvent expired = events.removeFirst();
            MemoryKey key = new MemoryKey(expired.guildId(), expired.channelId(), expired.userId());
            Map<String, Integer> tokenCounts = counts.get(key);
            if (tokenCounts != null) {
                for (Map.Entry<String, Integer> entry : expired.tokenCounts().entrySet()) {
                    tokenCounts.merge(entry.getKey(), -entry.getValue(), Integer::sum);
                    if (tokenCounts.get(entry.getKey()) <= 0) {
                        tokenCounts.remove(entry.getKey());
                    }
                }
                if (tokenCounts.isEmpty()) {
                    counts.remove(key);
                }
            }
            Deque<MemoryMessage> messages = recentMessages.get(key);
            if (messages != null) {
                pruneMessages(messages, now);
                if (messages.isEmpty()) {
                    recentMessages.remove(key);
                }
            }
            removed = true;
        }
        return removed;
    }

    private boolean isExpired(MemoryEvent event, Instant now) {
        Instant timestamp = Instant.ofEpochMilli(event.timestampMillis());
        return timestamp.isBefore(now.minus(retention));
    }

    private void appendEvent(MemoryEvent event) {
        try {
            ensureParentDirectory();
            try (BufferedWriter writer = Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
                writer.write(MAPPER.writeValueAsString(event));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("[WORD_MEMORY] Failed to append event: " + e.getMessage());
        }
    }

    private void rewriteFile() {
        try {
            ensureParentDirectory();
            try (BufferedWriter writer = Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                for (MemoryEvent event : events) {
                    writer.write(MAPPER.writeValueAsString(event));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            System.err.println("[WORD_MEMORY] Failed to compact store: " + e.getMessage());
        }
    }

    private void ensureParentDirectory() throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void pruneMessages(Deque<MemoryMessage> messages, Instant now) {
        if (messages == null) {
            return;
        }
        while (!messages.isEmpty()) {
            MemoryMessage first = messages.peekFirst();
            if (first == null) {
                messages.removeFirst();
                continue;
            }
            Instant timestamp = Instant.ofEpochMilli(first.timestampMillis());
            if (timestamp.isBefore(now.minus(retention))) {
                messages.removeFirst();
            } else {
                break;
            }
        }
    }

    private static List<String> tokenizeContent(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        String[] rawTokens = TOKEN_SPLIT.split(normalized.trim());
        List<String> words = new ArrayList<>();
        for (String token : rawTokens) {
            if (!token.isBlank()) {
                words.add(token);
            }
        }
        List<String> tokens = new ArrayList<>(words.size() * 2);
        tokens.addAll(words);
        for (int i = 0; i + 1 < words.size(); i++) {
            tokens.add(words.get(i) + " " + words.get(i + 1));
        }
        return tokens;
    }

    private static Map<String, Integer> buildTokenCounts(List<String> tokens) {
        Map<String, Integer> tokenCounts = new HashMap<>();
        for (String token : tokens) {
            String normalized = normalizeToken(token);
            if (normalized.isBlank()) {
                continue;
            }
            tokenCounts.merge(normalized, 1, Integer::sum);
        }
        return tokenCounts;
    }

    private static String normalizeToken(String token) {
        return token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
    }
}
