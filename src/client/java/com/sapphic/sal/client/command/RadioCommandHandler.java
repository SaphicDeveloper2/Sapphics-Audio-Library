package com.sapphic.sal.client.command;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.radio.RadioStreamController;
import com.sapphic.sal.network.RadioCommandPayload;
import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side handler for radio command packets from the server.
 * Receives radio command payloads and executes the actual radio playback.
 */
public class RadioCommandHandler {
    
    /** The current active stream session */
    private static final AtomicReference<RadioStreamController.RadioStreamSession> currentSession = new AtomicReference<>();
    
    /** Current volume setting */
    private static float currentVolume = 0.8f;
    
    /**
     * Registers the client-side packet receiver for radio commands.
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RadioCommandPayload.ID, RadioCommandHandler::handlePacket);
        Sapphicsaudiolib.LOGGER.debug("Registered radio command packet handler");
    }
    
    /**
     * Handles incoming radio command packets from the server.
     */
    private static void handlePacket(RadioCommandPayload payload, ClientPlayNetworking.Context context) {
        MinecraftClient client = context.client();
        
        // Execute on the main client thread
        client.execute(() -> {
            switch (payload.type()) {
                case PLAY -> playStation(payload.stationName());
                case STOP -> stopStation();
                case LIST -> listStations();
                case SET_VOLUME -> setVolume(payload.value());
                case STATUS -> showStatus();
            }
        });
    }
    
    /**
     * Plays a station by name.
     */
    private static void playStation(String stationName) {
        MinecraftClient client = MinecraftClient.getInstance();
        
        // Find the station
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
            sendClientMessage(Text.literal("Station not found: " + stationName)
                    .formatted(Formatting.RED));
            return;
        }
        
        // Stop any existing stream
        RadioStreamController.RadioStreamSession existing = currentSession.getAndSet(null);
        if (existing != null && existing.isPlaying()) {
            existing.stop();
        }
        
        final RadioStation finalStation = station;
        
        RadioStreamController.startStream(station, currentVolume)
                .thenAccept(session -> {
                    currentSession.set(session);
                    sendClientMessage(Text.literal("Now playing: ")
                            .formatted(Formatting.GREEN)
                            .append(Text.literal(finalStation.name())
                                    .formatted(Formatting.AQUA))
                            .append(Text.literal(" (" + finalStation.codec() + ")")
                                    .formatted(Formatting.GRAY)));
                })
                .exceptionally(error -> {
                    sendClientMessage(Text.literal("Failed to start stream: " + error.getMessage())
                            .formatted(Formatting.RED));
                    Sapphicsaudiolib.LOGGER.error("Radio command error", error);
                    return null;
                });
    }
    
    /**
     * Stops the current stream.
     */
    private static void stopStation() {
        RadioStreamController.RadioStreamSession session = currentSession.getAndSet(null);
        if (session != null && session.isPlaying()) {
            String stationName = session.getStation().name();
            session.stop();
            sendClientMessage(Text.literal("Stopped: ")
                    .formatted(Formatting.YELLOW)
                    .append(Text.literal(stationName)
                            .formatted(Formatting.WHITE)));
        } else {
            sendClientMessage(Text.literal("No radio currently playing")
                    .formatted(Formatting.GRAY));
        }
    }
    
    /**
     * Lists all registered stations.
     */
    private static void listStations() {
        Collection<RadioStation> stations = StationRegistry.getAll();
        
        if (stations.isEmpty()) {
            sendClientMessage(Text.literal("No stations registered")
                    .formatted(Formatting.GRAY));
            return;
        }
        
        sendClientMessage(Text.literal("=== Registered Radio Stations ===")
                .formatted(Formatting.GOLD));
        
        for (RadioStation station : stations) {
            sendClientMessage(Text.literal("• ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(station.name())
                            .formatted(Formatting.WHITE))
                    .append(Text.literal(" [" + station.codec() + "]")
                            .formatted(Formatting.DARK_GRAY)));
        }
        
        sendClientMessage(Text.literal("Use /radio play <name> to play")
                .formatted(Formatting.GRAY, Formatting.ITALIC));
    }
    
    /**
     * Sets the volume.
     */
    private static void setVolume(float volume) {
        currentVolume = volume;
        
        // Update current session if playing
        RadioStreamController.RadioStreamSession session = currentSession.get();
        if (session != null && session.isPlaying()) {
            session.setVolume(volume);
        }
        
        int percent = Math.round(volume * 100);
        sendClientMessage(Text.literal("Volume set to: ")
                .formatted(Formatting.GREEN)
                .append(Text.literal(percent + "%")
                        .formatted(Formatting.WHITE)));
    }
    
    /**
     * Shows current playback status.
     */
    private static void showStatus() {
        RadioStreamController.RadioStreamSession session = currentSession.get();
        
        sendClientMessage(Text.literal("=== Radio Status ===")
                .formatted(Formatting.GOLD));
        
        if (session != null && session.isPlaying()) {
            RadioStation station = session.getStation();
            sendClientMessage(Text.literal("Playing: ")
                    .formatted(Formatting.GREEN)
                    .append(Text.literal(station.name())
                            .formatted(Formatting.AQUA)));
            sendClientMessage(Text.literal("Codec: ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(station.codec())
                            .formatted(Formatting.WHITE)));
            sendClientMessage(Text.literal("Bitrate: ")
                    .formatted(Formatting.GRAY)
                    .append(Text.literal(station.bitrate() + " kbps")
                            .formatted(Formatting.WHITE)));
        } else {
            sendClientMessage(Text.literal("Not playing")
                    .formatted(Formatting.GRAY));
        }
        
        int percent = Math.round(currentVolume * 100);
        sendClientMessage(Text.literal("Volume: ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(percent + "%")
                        .formatted(Formatting.WHITE)));
        
        sendClientMessage(Text.literal("Active streams: ")
                .formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(RadioStreamController.getActiveStreamCount()))
                        .formatted(Formatting.WHITE)));
    }
    
    /**
     * Sends a message to the client's chat.
     */
    private static void sendClientMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(message, false);
        }
    }
    
    /**
     * Gets the current radio session (for external access).
     */
    public static RadioStreamController.RadioStreamSession getCurrentSession() {
        return currentSession.get();
    }
    
    /**
     * Gets the current volume setting.
     */
    public static float getCurrentVolume() {
        return currentVolume;
    }
}
