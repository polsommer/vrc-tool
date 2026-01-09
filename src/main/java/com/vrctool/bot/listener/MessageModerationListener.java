package com.vrctool.bot.listener;

import com.vrctool.bot.config.BotConfig;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageModerationListener extends ListenerAdapter {
    private record KeywordPattern(String keyword, Pattern pattern) {}

    private final BotConfig config;
    private final List<Pattern> blockedPatterns;
    private final List<KeywordPattern> keywordPatterns;

    public MessageModerationListener(BotConfig config) {
        this.config = config;
        this.blockedPatterns = compilePatterns(config.blockedPatterns());
        this.keywordPatterns = config.scanKeywords().stream()
                .map(keyword -> new KeywordPattern(keyword, compilePattern(keyword)))
                .toList();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Message message = event.getMessage();
        if (message.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        Member member = event.getMember();
        if (member == null || isStaff(member)) {
            return;
        }

        String content = message.getContentDisplay().toLowerCase(Locale.ROOT);
        boolean blocked = false;
        for (Pattern pattern : blockedPatterns) {
            if (pattern.matcher(content).find()) {
                message.delete().queue();
                message.getChannel().sendMessage(member.getAsMention() + " please avoid posting invite or scam links.")
                        .queue();
                logModeration(event.getChannel(), member, message.getContentDisplay());
                blocked = true;
                break;
            }
        }
        if (blocked) {
            return;
        }

        for (KeywordPattern keywordPattern : keywordPatterns) {
            if (keywordPattern.pattern().matcher(content).find()) {
                warnKeyword(event.getChannel(), member, keywordPattern.keyword());
                logKeywordWarning(event.getChannel(), member, message.getContentDisplay(), keywordPattern.keyword());
                break;
            }
        }
    }

    private boolean isStaff(Member member) {
        if (config.staffRoleId() == null) {
            return member.hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE);
        }
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(config.staffRoleId()));
    }

    private void logModeration(MessageChannel origin, Member member, String content) {
        if (config.modLogChannelId() == null) {
            return;
        }
        MessageChannel modChannel = origin.getJDA().getChannelById(MessageChannel.class, config.modLogChannelId());
        if (modChannel == null) {
            return;
        }
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Auto-moderation action")
                .addField("Member", member.getUser().getAsTag(), true)
                .addField("Channel", origin.getAsMention(), true)
                .addField("Content", content, false)
                .setTimestamp(Instant.now())
                .setColor(0xEF4444);
        modChannel.sendMessageEmbeds(builder.build()).queue();
    }

    private void warnKeyword(MessageChannel origin, Member member, String keyword) {
        origin.sendMessage(member.getAsMention()
                        + " please avoid discussing or sharing content related to **"
                        + keyword
                        + "**.")
                .queue();
    }

    private void logKeywordWarning(MessageChannel origin, Member member, String content, String keyword) {
        if (config.modLogChannelId() == null) {
            return;
        }
        MessageChannel modChannel = origin.getJDA().getChannelById(MessageChannel.class, config.modLogChannelId());
        if (modChannel == null) {
            return;
        }
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Keyword warning")
                .setDescription("Keyword match: **" + keyword + "**")
                .addField("Member", member.getUser().getAsTag(), true)
                .addField("Channel", origin.getAsMention(), true)
                .addField("Content", content, false)
                .setTimestamp(Instant.now())
                .setColor(0xF97316);
        modChannel.sendMessageEmbeds(builder.build()).queue();
    }

    private static List<Pattern> compilePatterns(List<String> terms) {
        return terms.stream()
                .map(MessageModerationListener::compilePattern)
                .toList();
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
