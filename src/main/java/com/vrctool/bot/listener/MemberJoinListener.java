package com.vrctool.bot.listener;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.service.TemplateService;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class MemberJoinListener extends ListenerAdapter {
    private final BotConfig config;
    private final TemplateService templateService;

    public MemberJoinListener(BotConfig config, TemplateService templateService) {
        this.config = config;
        this.templateService = templateService;
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (config.welcomeChannelId() == null) {
            return;
        }
        MessageChannel channel = event.getGuild().getChannelById(MessageChannel.class, config.welcomeChannelId());
        if (channel == null) {
            return;
        }
        channel.sendMessageEmbeds(templateService.buildWelcomeEmbed(event.getUser().getAsMention()))
                .queue();
    }
}
