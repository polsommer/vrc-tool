package com.vrctool.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrctool.bot.config.BotConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;

public final class ActivePlayersServer {
    private static final int MAX_MESSAGE_LENGTH = 1900;
    private final BotConfig config;
    private final ObjectMapper mapper;
    private HttpServer server;

    public ActivePlayersServer(BotConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
    }

    public void start(JDA jda) {
        if (config.activePlayersChannelId() == null || config.activePlayersChannelId().isBlank()) {
            System.out.println("[ACTIVE_PLAYERS] No channel ID configured; HTTP server not started.");
            return;
        }

        try {
            server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", config.activePlayersWebPort()),
                    0
            );
        } catch (IOException e) {
            System.err.println("[ACTIVE_PLAYERS] Failed to bind HTTP server: " + e.getMessage());
            return;
        }

        server.createContext("/active-players", exchange -> handleRequest(exchange, jda));
        server.setExecutor(null);
        server.start();
        System.out.println("[ACTIVE_PLAYERS] Listening on 127.0.0.1:" + config.activePlayersWebPort());
    }

    private void handleRequest(HttpExchange exchange, JDA jda) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method not allowed");
            return;
        }

        if (!isAuthorized(exchange)) {
            respond(exchange, 401, "Unauthorized");
            return;
        }

        ActivePlayersPayload payload;
        try {
            payload = mapper.readValue(exchange.getRequestBody(), ActivePlayersPayload.class);
        } catch (IOException e) {
            respond(exchange, 400, "Invalid JSON payload");
            return;
        }

        List<String> players = payload.players != null ? payload.players : List.of();
        TextChannel channel = jda.getTextChannelById(config.activePlayersChannelId());
        if (channel == null) {
            respond(exchange, 404, "Channel not found");
            return;
        }

        for (String message : buildMessages(players)) {
            channel.sendMessage(message).queue();
        }

        respond(exchange, 200, "OK");
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String token = config.activePlayersWebToken();
        if (token == null || token.isBlank()) {
            return true;
        }
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            String value = authHeader.substring("bearer ".length()).trim();
            return token.equals(value);
        }
        String tokenHeader = exchange.getRequestHeaders().getFirst("X-Auth-Token");
        return tokenHeader != null && tokenHeader.equals(token);
    }

    private List<String> buildMessages(List<String> players) {
        if (players.isEmpty()) {
            return List.of("Active players: none detected.");
        }

        List<String> messages = new ArrayList<>();
        String header = "Active players (" + players.size() + "): ";
        StringBuilder current = new StringBuilder(header);

        for (String player : players) {
            String entry = player.trim();
            if (entry.isEmpty()) {
                continue;
            }
            String prefix = current.length() == header.length() ? "" : ", ";
            if (current.length() + prefix.length() + entry.length() > MAX_MESSAGE_LENGTH) {
                messages.add(current.toString());
                current = new StringBuilder("Active players (cont.): ").append(entry);
                continue;
            }
            current.append(prefix).append(entry);
        }

        if (current.length() > 0) {
            messages.add(current.toString());
        }

        return messages;
    }

    private void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(payload);
        }
    }

    private static final class ActivePlayersPayload {
        public List<String> players;
        public Integer count;
        public String source;
    }
}
