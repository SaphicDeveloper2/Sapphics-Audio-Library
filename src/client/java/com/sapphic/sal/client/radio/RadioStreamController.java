package com.sapphic.sal.client.radio;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.HttpAudioStreamReader;
import com.sapphic.sal.client.audio.StreamingAudioPlayer;
import com.sapphic.sal.client.audio.codec.CodecRegistry;
import com.sapphic.sal.radio.RadioBrowserAPI;
import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;

import net.minecraft.util.math.Vec3d;

/**
 * Client-side radio streaming controller.
 * Handles connecting to internet radio streams and playing them through OpenAL.
 * 
 * <p>Supports multiple codecs via {@link CodecRegistry}:
 * <ul>
 *   <li>OGG Vorbis - Built-in support</li>
 *   <li>MP3 - Requires mp3spi library</li>
 *   <li>AAC - Requires jaad library</li>
 * </ul>
 */
public class RadioStreamController {
    
    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SapphicsAudioLib-RadioStream");
        t.setDaemon(true);
        return t;
    });
    
    // Active radio streams
    private static final Map<UUID, ActiveRadioStream> activeStreams = new ConcurrentHashMap<>();
    
    /**
     * Represents an active radio stream session.
     */
    public static class RadioStreamSession {
        private final UUID sessionId;
        private final RadioStation station;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private HttpAudioStreamReader.StreamConnection streamConnection;
        
        RadioStreamSession(UUID sessionId, RadioStation station) {
            this.sessionId = sessionId;
            this.station = station;
        }
        
        void setStreamConnection(HttpAudioStreamReader.StreamConnection conn) {
            this.streamConnection = conn;
        }
        
        /**
         * Gets the unique session ID.
         */
        public UUID getSessionId() {
            return sessionId;
        }
        
        /**
         * Gets the radio station being played.
         */
        public RadioStation getStation() {
            return station;
        }
        
        /**
         * Stops the radio stream.
         */
        public void stop() {
            stopped.set(true);
            RadioStreamController.stopStream(sessionId);
        }
        
        /**
         * Checks if the stream is still active.
         */
        public boolean isPlaying() {
            return !stopped.get() && activeStreams.containsKey(sessionId) &&
                   (streamConnection == null || streamConnection.isRunning());
        }
        
        /**
         * Sets the volume.
         * 
         * @param volume Volume level (0.0 to 1.0)
         */
        public void setVolume(float volume) {
            if (streamConnection != null && streamConnection.isRunning()) {
                streamConnection.getSession().setVolume(volume);
            }
        }
        
        /**
         * Pauses the stream.
         */
        public void pause() {
            // Streaming audio doesn't support pause in the same way
            // Just lower volume to 0
            setVolume(0);
        }
        
        /**
         * Resumes the stream.
         */
        public void resume() {
            // Restore default volume
            setVolume(1.0f);
        }
    }
    
    private static class ActiveRadioStream {
        final UUID sessionId;
        final RadioStation station;
        final AtomicBoolean running = new AtomicBoolean(true);
        HttpAudioStreamReader.StreamConnection streamConnection;
        
        ActiveRadioStream(UUID sessionId, RadioStation station) {
            this.sessionId = sessionId;
            this.station = station;
        }
    }
    
    /**
     * Starts streaming a radio station at the player's position (non-positional audio).
     * 
     * @param station The radio station to stream
     * @param volume Initial volume (0.0 to 1.0)
     * @return CompletableFuture containing the stream session
     */
    public static CompletableFuture<RadioStreamSession> startStream(RadioStation station, float volume) {
        return startStreamAtPosition(station, null, volume);
    }
    
    /**
     * Starts streaming a station from the registry by name.
     * 
     * @param stationName The registered station name
     * @param volume Initial volume (0.0 to 1.0)
     * @return CompletableFuture containing the stream session
     * @throws IllegalArgumentException if station not found in registry
     */
    public static CompletableFuture<RadioStreamSession> startStreamByName(String stationName, float volume) {
        RadioStation station = StationRegistry.getByName(stationName);
        if (station == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Station not found in registry: " + stationName));
        }
        return startStream(station, volume);
    }
    
    /**
     * Starts streaming the default UpBeat station.
     * 
     * @param volume Initial volume (0.0 to 1.0)
     * @return CompletableFuture containing the stream session
     */
    public static CompletableFuture<RadioStreamSession> startUpBeat(float volume) {
        RadioStation upbeat = StationRegistry.getUpBeat();
        if (upbeat == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("UpBeat station not found in registry"));
        }
        return startStream(upbeat, volume);
    }
    
    /**
     * Starts streaming the default UpBeat station at a 3D position.
     * 
     * @param position World position for 3D audio
     * @param volume Initial volume (0.0 to 1.0)
     * @return CompletableFuture containing the stream session
     */
    public static CompletableFuture<RadioStreamSession> startUpBeatAtPosition(Vec3d position, float volume) {
        RadioStation upbeat = StationRegistry.getUpBeat();
        if (upbeat == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("UpBeat station not found in registry"));
        }
        return startStreamAtPosition(upbeat, position, volume);
    }
    
    /**
     * Starts streaming a radio station at a specific position (3D audio).
     * 
     * @param station The radio station to stream
     * @param position World position for 3D audio (null for non-positional)
     * @param volume Initial volume (0.0 to 1.0)
     * @return CompletableFuture containing the stream session
     */
    public static CompletableFuture<RadioStreamSession> startStreamAtPosition(RadioStation station, Vec3d position, float volume) {
        UUID sessionId = UUID.randomUUID();
        
        return CompletableFuture.supplyAsync(() -> {
            // Register the click to support the Radio Browser project
            RadioBrowserAPI.registerClick(station.stationUuid());
            
            ActiveRadioStream stream = new ActiveRadioStream(sessionId, station);
            activeStreams.put(sessionId, stream);
            
            // Start the streaming using the new HttpAudioStreamReader
            RadioStreamSession session = new RadioStreamSession(sessionId, station);
            startStreaming(stream, session, volume);
            
            Sapphicsaudiolib.LOGGER.info("Started radio stream: {} ({})", 
                    station.name(), station.codec());
            
            return session;
        }, executor);
    }
    
    private static void startStreaming(ActiveRadioStream stream, RadioStreamSession session, float volume) {
        String streamUrl = stream.station.getStreamUrl();
        Sapphicsaudiolib.LOGGER.info("Connecting to radio stream: {}", streamUrl);
        
        // Use the new HttpAudioStreamReader for proper streaming
        HttpAudioStreamReader.StreamConnection conn = HttpAudioStreamReader.startStream(streamUrl, volume);
        
        if (conn == null) {
            Sapphicsaudiolib.LOGGER.error("Failed to start stream connection for {}", stream.station.name());
            activeStreams.remove(stream.sessionId);
            return;
        }
        
        stream.streamConnection = conn;
        session.setStreamConnection(conn);
        
        // Set up callbacks
        conn.onError(() -> {
            Sapphicsaudiolib.LOGGER.warn("Radio stream error for {}", stream.station.name());
            activeStreams.remove(stream.sessionId);
        });
        
        conn.onComplete(() -> {
            Sapphicsaudiolib.LOGGER.info("Radio stream completed for {}", stream.station.name());
            activeStreams.remove(stream.sessionId);
        });
        
        Sapphicsaudiolib.LOGGER.debug("Streaming connection established for {}", stream.station.name());
    }
    
    /**
     * Stops a radio stream by session ID.
     * 
     * @param sessionId The session to stop
     */
    public static void stopStream(UUID sessionId) {
        ActiveRadioStream stream = activeStreams.remove(sessionId);
        if (stream != null) {
            stream.running.set(false);
            if (stream.streamConnection != null) {
                stream.streamConnection.stop();
            }
            Sapphicsaudiolib.LOGGER.info("Stopped radio stream: {}", stream.station.name());
        }
    }
    
    /**
     * Stops all active radio streams.
     */
    public static void stopAllStreams() {
        activeStreams.keySet().forEach(RadioStreamController::stopStream);
        StreamingAudioPlayer.stopAll();
    }
    
    /**
     * Gets the number of active radio streams.
     */
    public static int getActiveStreamCount() {
        return activeStreams.size();
    }
    
    /**
     * Checks if a specific stream is active.
     */
    public static boolean isStreamActive(UUID sessionId) {
        return activeStreams.containsKey(sessionId);
    }
    
    /**
     * Gets the status of all available codecs.
     * 
     * @return Map of codec name to availability status
     */
    public static java.util.Map<String, Boolean> getCodecStatus() {
        return CodecRegistry.getStatus();
    }
    
    /**
     * Checks if a specific codec is available for radio streaming.
     * 
     * @param codecName Codec name (e.g., "MP3", "OGG", "AAC")
     * @return true if the codec is available
     */
    public static boolean isCodecAvailable(String codecName) {
        return CodecRegistry.isAvailable(codecName);
    }
}
