package com.vrctool.bot.service;

import com.vrctool.bot.config.BotConfig;
import java.time.Instant;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

public class TemplateService {
    private final BotConfig config;

    public TemplateService(BotConfig config) {
        this.config = config;
    }

    public MessageEmbed buildWelcomeEmbed(String memberName) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Welcome to the VRChat hub!")
                .setDescription("Hey " + memberName + "! We're excited to explore new worlds with you.")
                .setTimestamp(Instant.now())
                .setColor(0x7F5AF0);

        if (config.rulesLink() != null) {
            builder.addField("Rules", "Read them here: " + config.rulesLink(), false);
        }
        if (config.groupLink() != null) {
            builder.addField("VRC Group", config.groupLink(), false);
        }
        return builder.build();
    }

    public MessageEmbed buildAboutEmbed() {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("VRC Group Assistant")
                .setDescription("Helping the community stay organized with smart reminders, FAQs, and event tools.")
                .setColor(0x2CB67D);

        if (config.supportLink() != null) {
            builder.addField("Support", config.supportLink(), false);
        }
        return builder.build();
    }

    public MessageEmbed buildEventEmbed(String name, String time, String details, String hostMention) {
        return new EmbedBuilder()
                .setTitle("Community Event: " + name)
                .setDescription(details)
                .addField("Time", time, true)
                .addField("Host", hostMention, true)
                .setColor(0x3D5AFE)
                .setTimestamp(Instant.now())
                .build();
    }
}
