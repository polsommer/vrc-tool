package com.vrctool.bot.listener;

import com.vrctool.bot.config.BotConfig;
import com.vrctool.bot.service.FaqEntry;
import com.vrctool.bot.service.FaqService;
import com.vrctool.bot.service.TemplateService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

public class SlashCommandListener extends ListenerAdapter {
    private static final int MAX_PURGE = 100;

    private final BotConfig config;
    private final FaqService faqService;
    private final TemplateService templateService;

    public SlashCommandListener(BotConfig config, FaqService faqService, TemplateService templateService) {
        this.config = config;
        this.faqService = faqService;
        this.templateService = templateService;
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        OptionData faqTopicOption = new OptionData(OptionType.STRING, "topic", "Topic keyword", true)
                .addChoices(faqService.entries().stream()
                        .map(entry -> new net.dv8tion.jda.api.interactions.commands.Command.Choice(
                                entry.topic(), entry.topic()))
                        .toList());
        List<CommandData> commands = List.of(
                Commands.slash("ping", "Check bot latency."),
                Commands.slash("about", "Learn about the VRC group assistant."),
                Commands.slash("server-info", "Get stats about the server."),
                Commands.slash("faq", "Read quick answers about the group.")
                        .addOptions(faqTopicOption),
                Commands.slash("faq-search", "Ask a question and get the closest FAQ match.")
                        .addOption(OptionType.STRING, "question", "What do you need help with?", true),
                Commands.slash("event-create", "Post a structured event announcement.")
                        .addOption(OptionType.STRING, "name", "Event name", true)
                        .addOption(OptionType.STRING, "time", "Time and timezone", true)
                        .addOption(OptionType.STRING, "details", "Details about the event", true),
                Commands.slash("staff-alert", "Send an alert to staff or the mod log.")
                        .addOption(OptionType.STRING, "message", "Alert details for the staff team", true),
                Commands.slash("purge", "Remove a batch of recent messages.")
                        .addOption(OptionType.INTEGER, "amount", "How many messages to delete (1-100)", true)
                        .addOption(OptionType.CHANNEL, "channel", "Target channel (defaults to current)", false)
        );

        if (config.guildId() != null) {
            Guild guild = jda.getGuildById(config.guildId());
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue();
            }
        } else {
            jda.updateCommands().addCommands(commands).queue();
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ping" -> event.reply("Pong! " + event.getJDA().getGatewayPing() + "ms").queue();
            case "about" -> event.replyEmbeds(templateService.buildAboutEmbed()).queue();
            case "server-info" -> handleServerInfo(event);
            case "faq" -> handleFaq(event);
            case "faq-search" -> handleFaqSearch(event);
            case "event-create" -> handleEventCreate(event);
            case "staff-alert" -> handleStaffAlert(event);
            case "purge" -> handlePurge(event);
            default -> event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }

    private void handleServerInfo(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("Server info is only available inside a guild.").setEphemeral(true).queue();
            return;
        }
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Server Overview")
                .setDescription("Here is a quick snapshot of the server.")
                .addField("Members", String.valueOf(guild.getMemberCount()), true)
                .addField("Channels", String.valueOf(guild.getChannels().size()), true)
                .addField("Roles", String.valueOf(guild.getRoles().size()), true)
                .setColor(0xFF8906);
        event.replyEmbeds(builder.build()).queue();
    }

    private void handleFaq(SlashCommandInteractionEvent event) {
        String topic = Objects.requireNonNull(event.getOption("topic")).getAsString();
        faqService.findByTopic(topic)
                .ifPresentOrElse(entry -> event.replyEmbeds(buildFaqEmbed(entry)).queue(),
                        () -> event.reply("I don't recognize that topic yet.").setEphemeral(true).queue());
    }

    private void handleFaqSearch(SlashCommandInteractionEvent event) {
        String question = Objects.requireNonNull(event.getOption("question")).getAsString();
        faqService.findBestMatch(question).ifPresentOrElse(entry -> {
            EmbedBuilder builder = new EmbedBuilder()
                    .setTitle("Best FAQ match: " + entry.title())
                    .setDescription(entry.description())
                    .setColor(0x00C2FF);
            List<String> suggestions = faqService.suggestTopics(question, 3);
            if (!suggestions.isEmpty()) {
                builder.addField("Related topics", suggestions.stream()
                        .map(topic -> "• " + topic)
                        .collect(Collectors.joining("\n")), false);
            }
            event.replyEmbeds(builder.build()).queue();
        }, () -> event.reply("I couldn't find a close FAQ match. Try a different phrasing.")
                .setEphemeral(true)
                .queue());
    }

    private void handleEventCreate(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) {
            event.reply("Unable to identify host.").setEphemeral(true).queue();
            return;
        }
        String name = Objects.requireNonNull(event.getOption("name")).getAsString();
        String time = Objects.requireNonNull(event.getOption("time")).getAsString();
        String details = Objects.requireNonNull(event.getOption("details")).getAsString();

        String hostMention = member.getAsMention();
        event.replyEmbeds(templateService.buildEventEmbed(name, time, details, hostMention)).queue();

        if (config.eventPingRoleId() != null) {
            event.getHook().sendMessage("<@&" + config.eventPingRoleId() + "> event posted!")
                    .setEphemeral(false)
                    .queue(message -> message.delete().queueAfter(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private void handlePurge(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) {
            event.reply("Unable to identify requestor.").setEphemeral(true).queue();
            return;
        }
        if (!isStaff(member)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }
        int amount = Objects.requireNonNull(event.getOption("amount")).getAsInt();
        int clamped = Math.min(MAX_PURGE, Math.max(1, amount));
        GuildMessageChannel channel = resolveChannel(event);
        if (channel == null) {
            event.reply("Please choose a text channel within this server.").setEphemeral(true).queue();
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(14);
        channel.getHistory().retrievePast(clamped).queue(messages -> {
            List<Message> deletable = messages.stream()
                    .filter(message -> message.getTimeCreated().isAfter(cutoff))
                    .collect(Collectors.toList());
            if (deletable.isEmpty()) {
                event.reply("No recent messages were eligible for deletion.").setEphemeral(true).queue();
                return;
            }
            channel.deleteMessages(deletable).queue();
            event.reply("Purged " + deletable.size() + " messages in " + channel.getAsMention() + ".")
                    .setEphemeral(true)
                    .queue();
        });
    }

    private void handleStaffAlert(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        if (member == null) {
            event.reply("Unable to identify requestor.").setEphemeral(true).queue();
            return;
        }
        if (!isStaff(member)) {
            event.reply("You do not have permission to use this command.").setEphemeral(true).queue();
            return;
        }
        String message = Objects.requireNonNull(event.getOption("message")).getAsString();
        String staffMention = config.staffRoleId() == null ? "Staff" : "<@&" + config.staffRoleId() + ">";
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("Staff alert")
                .setDescription(message)
                .addField("Requested by", member.getAsMention(), true)
                .setTimestamp(OffsetDateTime.now())
                .setColor(0xFACC15);

        MessageChannel targetChannel = config.modLogChannelId() == null
                ? event.getChannel()
                : event.getJDA().getChannelById(MessageChannel.class, config.modLogChannelId());
        if (targetChannel != null) {
            targetChannel.sendMessage(staffMention)
                    .queue(post -> post.delete().queueAfter(5, java.util.concurrent.TimeUnit.SECONDS));
            targetChannel.sendMessageEmbeds(builder.build()).queue();
        }

        event.replyEmbeds(builder.build())
                .setEphemeral(true)
                .queue();
    }

    private GuildMessageChannel resolveChannel(SlashCommandInteractionEvent event) {
        OptionMapping channelOption = event.getOption("channel");
        if (channelOption == null) {
            if (event.getChannel() instanceof GuildMessageChannel guildChannel) {
                return guildChannel;
            }
            return null;
        }
        if (channelOption.getAsChannel() instanceof GuildMessageChannel guildChannel) {
            return guildChannel;
        }
        return null;
    }

    private boolean isStaff(Member member) {
        if (config.staffRoleId() == null) {
            return member.hasPermission(Permission.MESSAGE_MANAGE);
        }
        return member.getRoles().stream().anyMatch(role -> role.getId().equals(config.staffRoleId()));
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildFaqEmbed(FaqEntry entry) {
        return new EmbedBuilder()
                .setTitle(entry.title())
                .setDescription(entry.description())
                .setColor(0x00C2FF)
                .build();
    }
}
