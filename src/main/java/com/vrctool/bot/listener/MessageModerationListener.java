package com.vrctool.bot.listener;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.service.LlmHttpClient;
import com.vrctool.bot.service.ModerationDecisionEngine;
import com.vrctool.bot.service.WordMemoryStore;
import com.vrctool.bot.util.TextNormalizer;
import java.time.Instant;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageModerationListener extends ListenerAdapter {
    private final BotConfig config;
    private final ModerationDecisionEngine decisionEngine;
    private final WordMemoryStore wordMemoryStore;
    private final TextNormalizer textNormalizer;

    public MessageModerationListener(BotConfig config, WordMemoryStore wordMemoryStore, TextNormalizer textNormalizer) {
        this.config = config;
        this.wordMemoryStore = wordMemoryStore;
        this.textNormalizer = textNormalizer;
        this.decisionEngine = new ModerationDecisionEngine(
                config,
                wordMemoryStore,
                textNormalizer,
                new LlmHttpClient(config)
        );
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

        String content = textNormalizer.normalize(message.getContentDisplay());
        wordMemoryStore.recordMessage(
                event.getGuild().getId(),
                event.getChannel().getId(),
                member.getId(),
                content,
                message.getTimeCreated().toInstant()
        );
        ModerationDecisionEngine.Decision decision = decisionEngine.evaluate(message, member, event.getChannel());
        switch (decision.action()) {
            case DELETE -> {
                message.delete().queue();
                sendDeleteNotice(event.getChannel(), member, decision.context());
                logModerationAction(event.getChannel(), member, decision);
            }
            case WARN -> {
                sendWarning(event.getChannel(), member, decision.context());
                logModerationAction(event.getChannel(), member, decision);
            }
            case ESCALATE_TO_MODS -> {
                logEscalation(event.getChannel(), member, decision);
            }
            case ALLOW -> {
            }
        }
    }

    private boolean isStaff(Member member) {
        if (config.staffRoleId() == null) {
            return member.hasPermission(net.dv8tion.jda.api.Permission.MESSAGE_MANAGE);
        }
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(config.staffRoleId()));
    }

    private void logModerationAction(
            MessageChannel origin,
            Member member,
            ModerationDecisionEngine.Decision decision
    ) {
        MessageChannel modChannel = resolveModLogChannel(origin);
        if (modChannel == null) {
            return;
        }
        ModerationDecisionEngine.DecisionContext context = decision.context();
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Auto-moderation action: " + decision.action())
                .addField("Member", member.getUser().getAsTag(), true)
                .addField("Channel", origin.getAsMention(), true)
                .addField("Content", context.content(), false)
                .addField("Matched keyword", safeValue(context.matchedKeyword()), true)
                .addField("Blocked pattern", safeValue(context.blockedPattern()), true)
                .addField("LLM risk", safeValue(context.llmRiskLevel()), true)
                .addField("LLM rationale", safeValue(context.llmRationale()), false)
                .addField("Scores (base/format/history/channel/total)", String.format(
                        "%d / %d / %d / %d / %d",
                        context.baseRiskScore(),
                        context.messageRiskScore(),
                        context.historyRiskScore(),
                        context.channelRiskScore(),
                        context.totalRiskScore()
                ), false)
                .addField("LLM score floor", String.valueOf(context.llmScoreFloor()), true)
                .addField("Thresholds (warn/delete/escalate)", String.format(
                        "%d / %d / %d",
                        context.warnThreshold(),
                        context.deleteThreshold(),
                        context.escalateThreshold()
                ), true)
                .addField("Message stats (len/links/uppercase%)", String.format(
                        "%d / %d / %.0f%%",
                        context.messageLength(),
                        context.linkCount(),
                        context.uppercaseRatio() * 100
                ), true)
                .addField("History (recent matches/total tokens)", String.format(
                        "%d / %d",
                        context.recentKeywordMatches(),
                        context.totalRecentTokens()
                ), true)
                .setTimestamp(Instant.now())
                .setColor(0xEF4444);
        modChannel.sendMessageEmbeds(builder.build()).queue();
    }

    private void logEscalation(
            MessageChannel origin,
            Member member,
            ModerationDecisionEngine.Decision decision
    ) {
        MessageChannel modChannel = resolveModLogChannel(origin);
        MessageChannel escalationChannel = resolveEscalationChannel(origin);
        if (modChannel == null && escalationChannel == null) {
            return;
        }
        ModerationDecisionEngine.DecisionContext context = decision.context();
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Escalation needed: " + decision.action())
                .setDescription("Automated moderation flagged this message for manual review.")
                .addField("Member", member.getUser().getAsTag(), true)
                .addField("Channel", origin.getAsMention(), true)
                .addField("Content", context.content(), false)
                .addField("Matched keyword", safeValue(context.matchedKeyword()), true)
                .addField("Blocked pattern", safeValue(context.blockedPattern()), true)
                .addField("LLM risk", safeValue(context.llmRiskLevel()), true)
                .addField("LLM rationale", safeValue(context.llmRationale()), false)
                .addField("Scores (base/format/history/channel/total)", String.format(
                        "%d / %d / %d / %d / %d",
                        context.baseRiskScore(),
                        context.messageRiskScore(),
                        context.historyRiskScore(),
                        context.channelRiskScore(),
                        context.totalRiskScore()
                ), false)
                .addField("LLM score floor", String.valueOf(context.llmScoreFloor()), true)
                .addField("Thresholds (warn/delete/escalate)", String.format(
                        "%d / %d / %d",
                        context.warnThreshold(),
                        context.deleteThreshold(),
                        context.escalateThreshold()
                ), true)
                .addField("Message stats (len/links/uppercase%)", String.format(
                        "%d / %d / %.0f%%",
                        context.messageLength(),
                        context.linkCount(),
                        context.uppercaseRatio() * 100
                ), true)
                .addField("History (recent matches/total tokens)", String.format(
                        "%d / %d",
                        context.recentKeywordMatches(),
                        context.totalRecentTokens()
                ), true)
                .setTimestamp(Instant.now())
                .setColor(0xE11D48);
        if (modChannel != null) {
            modChannel.sendMessageEmbeds(builder.build()).queue();
        }
        if (escalationChannel != null && !escalationChannel.getId().equals(modChannel == null ? "" : modChannel.getId())) {
            escalationChannel.sendMessageEmbeds(builder.build()).queue();
        }
    }

    private void sendWarning(
            MessageChannel origin,
            Member member,
            ModerationDecisionEngine.DecisionContext context
    ) {
        if (context.matchedKeyword() != null) {
            origin.sendMessage(member.getAsMention()
                            + " please avoid discussing or sharing content related to **"
                            + context.matchedKeyword()
                            + "**.")
                    .queue();
            return;
        }
        origin.sendMessage(member.getAsMention()
                        + " please keep messages appropriate for this channel.")
                .queue();
    }

    private void sendDeleteNotice(
            MessageChannel origin,
            Member member,
            ModerationDecisionEngine.DecisionContext context
    ) {
        if (context.blockedPattern() != null) {
            origin.sendMessage(member.getAsMention()
                            + " please avoid posting invite or scam links.")
                    .queue();
            return;
        }
        origin.sendMessage(member.getAsMention()
                        + " your message was removed for moderation review.")
                .queue();
    }

    private MessageChannel resolveModLogChannel(MessageChannel origin) {
        if (config.modLogChannelId() == null) {
            return null;
        }
        return origin.getJDA().getChannelById(MessageChannel.class, config.modLogChannelId());
    }

    private MessageChannel resolveEscalationChannel(MessageChannel origin) {
        if (config.modEscalationChannelId() == null) {
            return null;
        }
        return origin.getJDA().getChannelById(MessageChannel.class, config.modEscalationChannelId());
    }

    private String safeValue(String value) {
        return value == null || value.isBlank() ? "None" : value;
    }

    private String safeValue(Object value) {
        return value == null ? "None" : value.toString();
    }

}
