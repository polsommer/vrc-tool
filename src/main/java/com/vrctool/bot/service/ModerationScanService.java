package com.vrctool.bot.service;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.util.ModerationPatterns;
import com.vrctool.bot.util.TextNormalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

public class ModerationScanService {
    private record KeywordPattern(String keyword, Pattern pattern) {}

    private static final Pattern MINOR_REFERENCE_PATTERN = Pattern.compile(
            "\\b(minor|underage|child|kid|teen|13|14|15|16|17)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ADULT_REFERENCE_PATTERN = Pattern.compile(
            "\\b(adult|18\\+|18\\s*plus|over\\s*18|18\\s*\\+)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RELATIONSHIP_CONTEXT_PATTERN = Pattern.compile(
            "\\b(cuddle|cuddling|dating|relationship|boyfriend|girlfriend|bf|gf|romantic|flirt|"
                    + "kiss|sexual|dm|dms|messages|screenshots|evidence|proof|gifting|gifted)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final BotConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, String> lastMessageIds;
    private final List<KeywordPattern> keywordPatterns;
    private final WordMemoryStore wordMemoryStore;
    private final TextNormalizer textNormalizer;

    public ModerationScanService(BotConfig config, WordMemoryStore wordMemoryStore, TextNormalizer textNormalizer) {
        this.config = config;
        this.wordMemoryStore = wordMemoryStore;
        this.textNormalizer = textNormalizer;
        int threadCount = Math.max(2, config.scanChannelIds().size());
        this.scheduler = Executors.newScheduledThreadPool(threadCount);
        this.lastMessageIds = new ConcurrentHashMap<>();
        this.keywordPatterns = config.scanKeywords().stream()
                .map(keyword -> new KeywordPattern(keyword, ModerationPatterns.compileKeywordPattern(keyword)))
                .toList();
    }

    public void start(JDA jda) {
        if (config.scanChannelIds().isEmpty()) {
            return;
        }
        long interval = config.scanInterval().toSeconds();
        for (String channelId : config.scanChannelIds()) {
            scheduler.scheduleAtFixedRate(() -> scanChannel(jda, channelId), interval, interval, TimeUnit.SECONDS);
        }
    }

    private void scanChannel(JDA jda, String channelId) {
        GuildMessageChannel channel = jda.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) {
            return;
        }
        String lastId = lastMessageIds.get(channel.getId());
        if (lastId == null) {
            channel.getHistory().retrievePast(20).queue(messages -> {
                processMessages(channel, messages);
                updateLastId(channel, messages);
            });
            return;
        }
        channel.getHistoryAfter(lastId, 50).queue(history -> {
            List<Message> messages = history.getRetrievedHistory();
            processMessages(channel, messages);
            updateLastId(channel, messages);
        });
    }

    private void processMessages(GuildMessageChannel channel, List<Message> messages) {
        messages.stream()
                .sorted(Comparator.comparing(Message::getTimeCreated))
                .forEach(message -> {
                    if (message.getAuthor().isBot() || message.isWebhookMessage()) {
                        return;
                    }
                    wordMemoryStore.recordMessage(
                            message.getGuild().getId(),
                            channel.getId(),
                            message.getAuthor().getId(),
                            textNormalizer.normalize(message.getContentDisplay()),
                            message.getTimeCreated().toInstant()
                    );
                    String content = message.getContentDisplay();
                    TextNormalizer.NormalizedResult normalizedResult = textNormalizer.normalizeAndExpand(content);
                    String normalized = normalizedResult.normalized();
                    String expanded = normalizedResult.expanded();
                    if (isAgeGapConcern(content, normalized, expanded)) {
                        logFlag(channel, message, "age gap (adult/minor)");
                        return;
                    }
                    for (KeywordPattern keywordPattern : keywordPatterns) {
                        if (matchesAny(keywordPattern.pattern(), content, normalized, expanded)) {
                            logFlag(channel, message, keywordPattern.keyword());
                            break;
                        }
                    }
                });
    }

    private static boolean matchesAny(Pattern pattern, String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && pattern.matcher(candidate).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAgeGapConcern(String content, String normalized, String expanded) {
        return matchesAny(MINOR_REFERENCE_PATTERN, content, normalized, expanded)
                && matchesAny(ADULT_REFERENCE_PATTERN, content, normalized, expanded)
                && matchesAny(RELATIONSHIP_CONTEXT_PATTERN, content, normalized, expanded);
    }

    private void updateLastId(GuildMessageChannel channel, List<Message> messages) {
        messages.stream()
                .max(Comparator.comparing(Message::getTimeCreated))
                .ifPresent(message -> lastMessageIds.put(channel.getId(), message.getId()));
    }

    private void logFlag(GuildMessageChannel origin, Message message, String keyword) {
        if (config.modLogChannelId() == null) {
            return;
        }
        MessageChannel modChannel = origin.getJDA().getChannelById(MessageChannel.class, config.modLogChannelId());
        if (modChannel == null) {
            return;
        }
        Member member = message.getMember();
        String author = member == null ? message.getAuthor().getAsTag() : member.getUser().getAsTag();
        int recentCount = wordMemoryStore.getTokenCount(
                message.getGuild().getId(),
                origin.getId(),
                message.getAuthor().getId(),
                keyword
        );
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Flagged message scan")
                .setDescription("Keyword match: **" + keyword + "**")
                .addField("Member", author, true)
                .addField("Channel", origin.getAsMention(), true)
                .addField("Content", message.getContentDisplay(), false)
                .addField("Recent matches (30d)", String.valueOf(recentCount), true)
                .setTimestamp(Instant.now())
                .setColor(0xF97316);
        modChannel.sendMessageEmbeds(builder.build()).queue();
    }

}
