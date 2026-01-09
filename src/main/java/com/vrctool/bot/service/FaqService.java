package com.vrctool.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
}
