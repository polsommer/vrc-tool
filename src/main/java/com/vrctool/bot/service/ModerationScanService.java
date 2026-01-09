package com.vrctool.bot.service;

import com.vrctool.bot.config.BotConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private final BotConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, String> lastMessageIds;
    private final List<KeywordPattern> keywordPatterns;

    public ModerationScanService(BotConfig config) {
        this.config = config;
        int threadCount = Math.max(2, config.scanChannelIds().size());
        this.scheduler = Executors.newScheduledThreadPool(threadCount);
        this.lastMessageIds = new ConcurrentHashMap<>();
        this.keywordPatterns = config.scanKeywords().stream()
                .map(keyword -> new KeywordPattern(keyword, compilePattern(keyword)))
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
                    String content = message.getContentDisplay().toLowerCase(Locale.ROOT);
                    for (KeywordPattern keywordPattern : keywordPatterns) {
                        if (keywordPattern.pattern().matcher(content).find()) {
                            logFlag(channel, message, keywordPattern.keyword());
                            break;
                        }
                    }
                });
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
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Flagged message scan")
                .setDescription("Keyword match: **" + keyword + "**")
                .addField("Member", author, true)
                .addField("Channel", origin.getAsMention(), true)
                .addField("Content", message.getContentDisplay(), false)
                .setTimestamp(Instant.now())
                .setColor(0xF97316);
        modChannel.sendMessageEmbeds(builder.build()).queue();
    }

    private static Pattern compilePattern(String term) {
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

    private static String obfuscationPattern(char value) {
        return switch (Character.toLowerCase(value)) {
            case 'a' -> "[a@]";
            case 'o' -> "[o0]";
            default -> null;
        };
    }
}
