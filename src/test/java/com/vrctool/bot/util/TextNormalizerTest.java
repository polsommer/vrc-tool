package com.vrctool.bot.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextNormalizerTest {

    @Test
    void normalizeRemovesPunctuationEmojiAndCollapsesWhitespace() {
        TextNormalizer normalizer = new TextNormalizer(
                java.util.Map.of(),
                TextNormalizer.MorphologyMode.NONE
        );
        String normalized = normalizer.normalize(" Hello,   WORLD!! 👋 ");
        assertEquals("hello world", normalized);
    }

    @Test
    void expandsSynonymsAfterStemming() {
        TextNormalizer normalizer = TextNormalizer.fromResource(
                "moderation-synonyms.json",
                TextNormalizer.MorphologyMode.STEM
        );
        TextNormalizer.NormalizedResult result = normalizer.normalizeAndExpand("Stop bullying people!");
        assertEquals("stop bully people", result.normalized());
        assertTrue(result.expanded().contains("harass"));
        assertTrue(result.expanded().contains("intimidate"));
    }
}
