package com.sapphic.sal.client.radio;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.AudioDecoder;
import com.sapphic.sal.client.audio.AudioEngine;
import com.sapphic.sal.client.audio.codec.CodecRegistry;
import com.sapphic.sal.radio.RadioBrowserAPI;
import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;
import net.minecraft.util.math.Vec3d;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
        
        RadioStreamSession(UUID sessionId, RadioStation station) {
            this.sessionId = sessionId;
            this.station = station;
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
            return !stopped.get() && activeStreams.containsKey(sessionId);
        }
        
        /**
         * Sets the volume.
         * 
         * @param volume Volume level (0.0 to 1.0)
         */
        public void setVolume(float volume) {
            AudioEngine.setVolume(sessionId, volume);
        }
        
        /**
         * Pauses the stream.
         */
        public void pause() {
            AudioEngine.pause(sessionId);
        }
        
        /**
         * Resumes the stream.
         */
        public void resume() {
            AudioEngine.resume(sessionId);
        }
    }
    
    private static class ActiveRadioStream {
        final UUID sessionId;
        final RadioStation station;
        final AtomicBoolean running = new AtomicBoolean(true);
        HttpURLConnection connection;
        
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
            
            // Start the streaming thread
            executor.submit(() -> streamRadio(stream, position, volume));
            
            Sapphicsaudiolib.LOGGER.info("Started radio stream: {} ({})", 
                    station.name(), station.codec());
            
            return new RadioStreamSession(sessionId, station);
        }, executor);
    }
    
    private static void streamRadio(ActiveRadioStream stream, Vec3d position, float volume) {
        try {
            String streamUrl = stream.station.getStreamUrl();
            URL url = URI.create(streamUrl).toURL();
            
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "SapphicsAudioLib/1.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            stream.connection = conn;
            
            conn.connect();
            
            if (conn.getResponseCode() != 200) {
                Sapphicsaudiolib.LOGGER.warn("Radio stream returned HTTP {}", conn.getResponseCode());
                return;
            }
            
            // Check content type and codec availability
            String contentType = conn.getContentType();
            String codec = stream.station.codec();
            Sapphicsaudiolib.LOGGER.debug("Radio stream content type: {}, codec: {}", contentType, codec);
            
            // Check if we have a decoder for this codec
            if (!CodecRegistry.isAvailable(codec)) {
                String available = String.join(", ", CodecRegistry.getNames());
                Sapphicsaudiolib.LOGGER.warn("Radio station {} uses {} codec which may not be supported. " +
                        "Available codecs: {}. Add mp3spi/jaad libraries for additional codec support.",
                        stream.station.name(), codec, available);
            }
            
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int bytesRead;
            
            // Buffer initial data before starting playback
            int initialBufferSize = 64 * 1024; // 64KB initial buffer
            while (stream.running.get() && buffer.size() < initialBufferSize) {
                bytesRead = in.read(chunk);
                if (bytesRead <= 0) break;
                buffer.write(chunk, 0, bytesRead);
            }
            
            if (buffer.size() > 0) {
                // Play the buffered audio using codec auto-detection
                byte[] audioData = buffer.toByteArray();
                double x = position != null ? position.x : 0;
                double y = position != null ? position.y : 0;
                double z = position != null ? position.z : 0;
                
                // If not positional, use a non-attenuated distance
                float maxDist = position != null ? 64.0f : 10000.0f;
                
                // AudioEngine.play uses CodecRegistry internally for auto-detection
                AudioEngine.play(stream.sessionId, audioData, -1, x, y, z, volume, 1.0f, maxDist);
                
                Sapphicsaudiolib.LOGGER.debug("Started radio playback with {} bytes buffered", audioData.length);
            }
            
            // Continue streaming and updating buffer
            // Note: This is a simplified implementation - real radio streaming would
            // require more sophisticated buffer management and seamless audio stitching
            while (stream.running.get()) {
                bytesRead = in.read(chunk);
                if (bytesRead <= 0) {
                    Sapphicsaudiolib.LOGGER.debug("Radio stream ended");
                    break;
                }
                // For continuous streaming, we'd need to queue additional buffers
                // This is left as a hook for future implementation
            }
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.warn("Radio stream error: {}", e.getMessage());
        } finally {
            activeStreams.remove(stream.sessionId);
            if (stream.connection != null) {
                stream.connection.disconnect();
            }
        }
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
            if (stream.connection != null) {
                stream.connection.disconnect();
            }
            AudioEngine.stop(sessionId);
            Sapphicsaudiolib.LOGGER.info("Stopped radio stream: {}", stream.station.name());
        }
    }
    
    /**
     * Stops all active radio streams.
     */
    public static void stopAllStreams() {
        activeStreams.keySet().forEach(RadioStreamController::stopStream);
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
