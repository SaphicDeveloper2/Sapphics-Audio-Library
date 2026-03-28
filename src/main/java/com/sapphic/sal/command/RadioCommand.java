package com.sapphic.sal.command;

import java.util.Collection;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.network.RadioCommandPayload;
import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;
import com.sapphic.sal.radio.UpBeatAPI;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
 * - /radio nowplaying - Show current song on UpBeat
 * - /radio recent [count] - Show recently played on UpBeat
 * - /radio schedule - Show UpBeat schedule
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
                
                // /radio nowplaying - UpBeat now playing
                .then(CommandManager.literal("nowplaying")
                    .executes(RadioCommand::showNowPlaying))
                
                // /radio np - alias for nowplaying
                .then(CommandManager.literal("np")
                    .executes(RadioCommand::showNowPlaying))
                
                // /radio recent [count]
                .then(CommandManager.literal("recent")
                    .executes(ctx -> showRecentlyPlayed(ctx, 5))
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 20))
                        .executes(ctx -> showRecentlyPlayed(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                
                // /radio schedule
                .then(CommandManager.literal("schedule")
                    .executes(RadioCommand::showSchedule))
                
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
        
        source.sendFeedback(() -> Text.literal("=== UpBeat Commands ===")
                .formatted(Formatting.LIGHT_PURPLE), false);
        source.sendFeedback(() -> Text.literal("/radio nowplaying")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Current song on UpBeat")
                        .formatted(Formatting.GRAY)), false);
        source.sendFeedback(() -> Text.literal("/radio recent [count]")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Recently played")
                        .formatted(Formatting.GRAY)), false);
        source.sendFeedback(() -> Text.literal("/radio schedule")
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" - Show schedule")
                        .formatted(Formatting.GRAY)), false);
        
        return 1;
    }
    
    // ==================== UPBEAT API COMMANDS ====================
    
    /**
     * Shows the current song playing on UpBeat.
     */
    private static int showNowPlaying(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        source.sendFeedback(() -> Text.literal("Fetching UpBeat stats...")
                .formatted(Formatting.GRAY, Formatting.ITALIC), false);
        
        UpBeatAPI.getStats().thenAccept(stats -> {
            if (stats == null) {
                source.sendError(Text.literal("Failed to fetch UpBeat stats")
                        .formatted(Formatting.RED));
                return;
            }
            
            // Header
            source.sendFeedback(() -> Text.literal("══════ ")
                    .formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal("♫ UpBeat Radio ♫")
                            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
                    .append(Text.literal(" ══════")
                            .formatted(Formatting.LIGHT_PURPLE)), false);
            
            // Current song
            if (stats.currentSong() != null) {
                UpBeatAPI.SongInfo song = stats.currentSong();
                
                MutableText songText = Text.literal("Now Playing: ")
                        .formatted(Formatting.GOLD);
                songText.append(Text.literal(song.title())
                        .formatted(Formatting.WHITE, Formatting.BOLD));
                songText.append(Text.literal(" by ")
                        .formatted(Formatting.GRAY));
                songText.append(Text.literal(song.artist())
                        .formatted(Formatting.AQUA));
                
                // Add Spotify link if available
                if (song.hasSpotify()) {
                    MutableText spotifyLink = Text.literal(" [Spotify]")
                            .formatted(Formatting.GREEN);
                    spotifyLink.styled(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, 
                                    "https://open.spotify.com/track/" + song.spotifyId()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                                    Text.literal("Open on Spotify"))));
                    songText.append(spotifyLink);
                }
                
                source.sendFeedback(() -> songText, false);
                
                // Stats line
                source.sendFeedback(() -> Text.literal("  ❤ ")
                        .formatted(Formatting.RED)
                        .append(Text.literal(String.valueOf(Math.max(0, song.likes())))
                                .formatted(Formatting.WHITE))
                        .append(Text.literal("  ★ ")
                                .formatted(Formatting.GOLD))
                        .append(Text.literal(String.valueOf(Math.max(0, song.favourites())))
                                .formatted(Formatting.WHITE))
                        .append(Text.literal("  ▶ ")
                                .formatted(Formatting.GREEN))
                        .append(Text.literal(String.valueOf(song.playCount()) + " plays")
                                .formatted(Formatting.WHITE)), false);
            }
            
            // Presenter
            if (stats.presenter() != null) {
                UpBeatAPI.Presenter dj = stats.presenter();
                
                MutableText djText = Text.literal("On Air: ")
                        .formatted(Formatting.YELLOW);
                
                if (dj.isAutoDJ()) {
                    djText.append(Text.literal("AutoDJ (UpBeat Stream)")
                            .formatted(Formatting.GRAY, Formatting.ITALIC));
                } else {
                    MutableText nameText = Text.literal(dj.name())
                            .formatted(Formatting.WHITE, Formatting.BOLD);
                    if (dj.profileUrl() != null) {
                        nameText.styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, dj.profileUrl()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                                        Text.literal("View profile"))));
                    }
                    djText.append(nameText);
                    
                    if (dj.isShow()) {
                        djText.append(Text.literal(" [LIVE SHOW]")
                                .formatted(Formatting.RED, Formatting.BOLD));
                    }
                }
                
                source.sendFeedback(() -> djText, false);
            }
            
            // Listener count
            source.sendFeedback(() -> Text.literal("Listeners: ")
                    .formatted(Formatting.AQUA)
                    .append(Text.literal(String.valueOf(stats.listeners()))
                            .formatted(Formatting.WHITE, Formatting.BOLD)), false);
            
            // Play button hint
            source.sendFeedback(() -> Text.literal("Use ")
                    .formatted(Formatting.GRAY, Formatting.ITALIC)
                    .append(Text.literal("/radio play UpBeat")
                            .formatted(Formatting.YELLOW))
                    .append(Text.literal(" to listen!")
                            .formatted(Formatting.GRAY, Formatting.ITALIC)), false);
        });
        
        return 1;
    }
    
    /**
     * Shows recently played songs on UpBeat.
     */
    private static int showRecentlyPlayed(CommandContext<ServerCommandSource> ctx, int count) {
        ServerCommandSource source = ctx.getSource();
        
        source.sendFeedback(() -> Text.literal("Fetching recently played...")
                .formatted(Formatting.GRAY, Formatting.ITALIC), false);
        
        UpBeatAPI.getRecentlyPlayed(count).thenAccept(songs -> {
            if (songs.isEmpty()) {
                source.sendError(Text.literal("Failed to fetch recently played")
                        .formatted(Formatting.RED));
                return;
            }
            
            source.sendFeedback(() -> Text.literal("══════ ")
                    .formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal("Recently Played")
                            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
                    .append(Text.literal(" ══════")
                            .formatted(Formatting.LIGHT_PURPLE)), false);
            
            int index = 1;
            for (UpBeatAPI.RecentSong song : songs) {
                final int num = index++;
                final String timeAgo = song.getTimeAgo();
                
                MutableText line = Text.literal(String.format("%2d. ", num))
                        .formatted(Formatting.GRAY);
                line.append(Text.literal(song.title())
                        .formatted(Formatting.WHITE));
                line.append(Text.literal(" - ")
                        .formatted(Formatting.DARK_GRAY));
                line.append(Text.literal(song.artist())
                        .formatted(Formatting.AQUA));
                line.append(Text.literal(" (" + timeAgo + ")")
                        .formatted(Formatting.DARK_GRAY, Formatting.ITALIC));
                
                source.sendFeedback(() -> line, false);
            }
        });
        
        return 1;
    }
    
    /**
     * Shows the UpBeat schedule.
     */
    private static int showSchedule(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        
        source.sendFeedback(() -> Text.literal("Fetching schedule...")
                .formatted(Formatting.GRAY, Formatting.ITALIC), false);
        
        UpBeatAPI.getSchedule().thenAccept(slots -> {
            if (slots.isEmpty()) {
                source.sendFeedback(() -> Text.literal("No shows scheduled this week")
                        .formatted(Formatting.GRAY), false);
                return;
            }
            
            source.sendFeedback(() -> Text.literal("══════ ")
                    .formatted(Formatting.LIGHT_PURPLE)
                    .append(Text.literal("UpBeat Schedule")
                            .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD))
                    .append(Text.literal(" ══════")
                            .formatted(Formatting.LIGHT_PURPLE)), false);
            
            // Group by day
            int currentDay = -1;
            for (UpBeatAPI.BookedSlot slot : slots) {
                if (slot.day() != currentDay) {
                    currentDay = slot.day();
                    final String dayName = slot.getDayName();
                    source.sendFeedback(() -> Text.literal("─── " + dayName + " ───")
                            .formatted(Formatting.GOLD), false);
                }
                
                final String time = slot.getTimeString();
                final String name = slot.name();
                
                MutableText line = Text.literal("  " + time + " ")
                        .formatted(Formatting.GRAY);
                
                MutableText nameText = Text.literal(name)
                        .formatted(Formatting.WHITE);
                if (slot.profileUrl() != null) {
                    nameText.styled(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, slot.profileUrl()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                                    Text.literal("View " + name + "'s profile"))));
                }
                line.append(nameText);
                
                source.sendFeedback(() -> line, false);
            }
            
            source.sendFeedback(() -> Text.literal("Times shown in UTC")
                    .formatted(Formatting.DARK_GRAY, Formatting.ITALIC), false);
        });
        
        return 1;
    }
}
