package com.vrctool.bot.listener;

import com.vrctool.bot.config.BotConfig;
import java.time.Instant;
import java.util.Locale;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MessageModerationListener extends ListenerAdapter {
    private static final String[] BLOCKED_PATTERNS = {
            "discord.gg",
            "discord.com/invite",
            "free nitro",
            "steamgift"
    };

    private final BotConfig config;

    public MessageModerationListener(BotConfig config) {
        this.config = config;
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
        for (String pattern : BLOCKED_PATTERNS) {
            if (content.contains(pattern)) {
                message.delete().queue();
                message.getChannel().sendMessage(member.getAsMention() + " please avoid posting invite or scam links.")
                        .queue();
                logModeration(event.getChannel(), member, message.getContentDisplay());
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
}
