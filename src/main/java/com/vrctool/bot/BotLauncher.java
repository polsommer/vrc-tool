package com.vrctool.bot;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.listener.MemberJoinListener;
import com.vrctool.bot.listener.MessageModerationListener;
import com.vrctool.bot.listener.SlashCommandListener;
import com.vrctool.bot.service.FaqService;
import com.vrctool.bot.service.ModerationScanService;
import com.vrctool.bot.service.TemplateService;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

public final class BotLauncher {
    public static void main(String[] args) throws InterruptedException {
        BotConfig config = BotConfig.fromEnvironment();
        FaqService faqService = new FaqService("/faq.json");
        TemplateService templateService = new TemplateService(config);
        ModerationScanService scanService = new ModerationScanService(config);

        JDA jda = JDABuilder.createDefault(config.discordToken())
                .enableIntents(List.of(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS
                ))
                .setMemberCachePolicy(MemberCachePolicy.ONLINE)
                .addEventListeners(
                        new SlashCommandListener(config, faqService, templateService),
                        new MemberJoinListener(config, templateService),
                        new MessageModerationListener(config)
                )
                .build();

        jda.awaitReady();
        scanService.start(jda);
    }
}
