package com.sapphic.sal.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.network.RadioCommandPayload;
import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collection;

/**
 * Server-side debug commands for testing radio streaming.
 * Sends packets to the executing player's client to control radio playback.
 * 
 * Commands:
 * - /radio play <stationname> - Play a station from the registry
 * - /radio stop - Stop the current stream
 * - /radio list - List all registered stations
 * - /radio volume <0.0-1.0> - Set playback volume
 * - /radio status - Show current playback status
 */
public class RadioCommand {
    
    /**
     * Registers the /radio command.
     */
    public static void register() {
        CommandRegistrationCallback.EVENT.register(RadioCommand::registerCommands);
        Sapphicsaudiolib.LOGGER.debug("Registered /radio debug command");
    }
    
    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, 
            CommandRegistryAccess registryAccess, 
            CommandManager.RegistrationEnvironment environment) {
        
        dispatcher.register(
            CommandManager.literal("radio")
                // /radio play <stationname>
                .then(CommandManager.literal("play")
                    .then(CommandManager.argument("station", StringArgumentType.greedyString())
                        .suggests(stationSuggestions())
                        .executes(ctx -> playStation(ctx, StringArgumentType.getString(ctx, "station")))))
                
                // /radio stop
                .then(CommandManager.literal("stop")
                    .executes(RadioCommand::stopStation))
                
                // /radio list
                .then(CommandManager.literal("list")
                    .executes(RadioCommand::listStations))
                
                // /radio volume <0.0-1.0>
                .then(CommandManager.literal("volume")
                    .then(CommandManager.argument("level", FloatArgumentType.floatArg(0.0f, 1.0f))
                        .executes(ctx -> setVolume(ctx, FloatArgumentType.getFloat(ctx, "level")))))
                
                // /radio status
                .then(CommandManager.literal("status")
                    .executes(RadioCommand::showStatus))
                
                // /radio (no args) - show help
                .executes(RadioCommand::showHelp)
        );
    }
    
    /**
     * Provides tab-completion suggestions for station names.
     */
    private static SuggestionProvider<ServerCommandSource> stationSuggestions() {
        return (context, builder) -> {
            Collection<RadioStation> stations = StationRegistry.getAll();
            for (RadioStation station : stations) {
                builder.suggest(station.name());
            }
            return builder.buildFuture();
        };
    }
    
    /**
     * Plays a station by name - sends packet to client.
     */
    private static int playStation(CommandContext<ServerCommandSource> ctx, String stationName) {
        ServerCommandSource source = ctx.getSource();
        
        // Validate station exists on server side
        RadioStation station = StationRegistry.getByName(stationName);
        if (station == null) {
            // Try partial match
            for (RadioStation s : StationRegistry.getAll()) {
                if (s.name().toLowerCase().contains(stationName.toLowerCase())) {
                    station = s;
                    break;
                }
            }
        }
        
        if (station == null) {
            source.sendError(Text.literal("Station not found: " + stationName)
                    .formatted(Formatting.RED));
            source.sendFeedback(() -> Text.literal("Use /radio list to see available stations")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        
        // Send packet to client
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            final String stationNameToPlay = station.name();
            ServerPlayNetworking.send(player, RadioCommandPayload.play(stationNameToPlay));
            source.sendFeedback(() -> Text.literal("Starting radio: ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(stationNameToPlay)
                            .formatted(Formatting.YELLOW)), false);
        } else {
            source.sendError(Text.literal("This command must be run by a player")
                    .formatted(Formatting.RED));
            return 0;
        }
        
        return 1;
    }
    
    /**
     * Stops the current stream - sends packet to client.
     */
    private static int stopStation(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            ServerPlayNetworking.send(player, RadioCommandPayload.stop());
            source.sendFeedback(() -> Text.literal("Stopping radio...")
                    .formatted(Formatting.YELLOW), false);
        } else {
            source.sendError(Text.literal("This command must be run by a player")
                    .formatted(Formatting.RED));
            return 0;
        }
        
        return 1;
    }
    
    /**
     * Lists all registered stations - runs locally, no packet needed.
     */
    private static int listStations(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        Collection<RadioStation> stations = StationRegistry.getAll();
        
        if (stations.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No stations registered")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }
        
        source.sendFeedback(() -> Text.literal("=== Registered Radio Stations ===")
                .formatted(Formatting.GOLD), false);
        
        for (RadioStation station : stations) {
            final RadioStation s = station;
            source.sendFeedback(() -> Text.literal("• ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(s.name())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" [" + s.codec() + "]")
                            .formatted(Formatting.DARK_GRAY)), false);
        }
        
        source.sendFeedback(() -> Text.literal("Use /radio play <name> to play")
                .formatted(Formatting.GRAY, Formatting.ITALIC), false);
        
        return stations.size();
    }
    
    /**
     * Sets the volume - sends packet to client.
     */
    private static int setVolume(CommandContext<ServerCommandSource> ctx, float volume) {
        ServerCommandSource source = ctx.getSource();
        
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            ServerPlayNetworking.send(player, RadioCommandPayload.setVolume(volume));
            int percent = Math.round(volume * 100);
            source.sendFeedback(() -> Text.literal("Volume set to: ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(percent + "%")
                            .formatted(Formatting.WHITE)), false);
        } else {
            source.sendError(Text.literal("This command must be run by a player")
                    .formatted(Formatting.RED));
            return 0;
        }
        
        return 1;
    }
    
    /**
     * Shows current playback status - sends packet to client.
     */
    private static int showStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            ServerPlayNetworking.send(player, RadioCommandPayload.status());
            // Status response comes back from client as chat messages
        } else {
            source.sendError(Text.literal("This command must be run by a player")
                    .formatted(Formatting.RED));
            return 0;
        }
        
        return 1;
    }
    
    /**
     * Shows command help.
     */
    private static int showHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        source.sendFeedback(() -> Text.literal("=== Radio Commands ===")
                .formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("/radio play <name>")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Play a station")
                        .formatted(Formatting.GRAY)), false);
        source.sendFeedback(() -> Text.literal("/radio stop")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Stop playback")
                        .formatted(Formatting.GRAY)), false);
        source.sendFeedback(() -> Text.literal("/radio list")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - List stations")
                        .formatted(Formatting.GRAY)), false);
        source.sendFeedback(() -> Text.literal("/radio volume <0.0-1.0>")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Set volume")
                        .formatted(Formatting.GRAY)), false);
        source.sendFeedback(() -> Text.literal("/radio status")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Show status")
                        .formatted(Formatting.GRAY)), false);
        
        return 1;
    }
}
