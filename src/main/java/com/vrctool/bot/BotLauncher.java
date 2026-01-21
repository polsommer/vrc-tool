package com.vrctool.bot;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.listener.MemberJoinListener;
import com.vrctool.bot.listener.MessageModerationListener;
import com.vrctool.bot.listener.SlashCommandListener;
import com.vrctool.bot.service.ActivePlayersServer;
import com.vrctool.bot.service.FaqService;
import com.vrctool.bot.service.ModerationScanService;
import com.vrctool.bot.service.TemplateService;
import com.vrctool.bot.service.WordMemoryStore;
import com.vrctool.bot.util.TextNormalizer;
import java.nio.file.Paths;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;

public final class BotLauncher {
    public static void main(String[] args) throws InterruptedException {
        BotConfig config;
        try {
            config = BotConfig.fromEnvironment();
        } catch (IllegalStateException error) {
            System.err.println("Bot configuration error: " + error.getMessage());
            System.err.println("Set DISCORD_TOKEN and any required IDs before launching the bot.");
            return;
        }
        FaqService faqService = new FaqService("/faq.json");
        TemplateService templateService = new TemplateService(config);
        WordMemoryStore wordMemoryStore = new WordMemoryStore(Paths.get(config.wordMemoryPath()));
        wordMemoryStore.load();
        TextNormalizer textNormalizer = TextNormalizer.fromResource(
                "moderation-synonyms.json",
                TextNormalizer.MorphologyMode.STEM
        );
        ModerationScanService scanService = new ModerationScanService(config, wordMemoryStore, textNormalizer);

        JDA jda = JDABuilder.createDefault(config.discordToken())
                .enableIntents(List.of(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_MEMBERS
                ))
                .setMemberCachePolicy(MemberCachePolicy.ONLINE)
                .addEventListeners(
                        new MemberJoinListener(config, templateService),
                        new MessageModerationListener(config, wordMemoryStore, textNormalizer),
                        new SlashCommandListener(config, faqService, templateService)
                )
                .build();

        jda.awaitReady();
        scanService.start(jda);
        new ActivePlayersServer(config).start(jda);
    }
}
