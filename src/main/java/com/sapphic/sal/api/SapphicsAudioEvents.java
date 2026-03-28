package com.sapphic.sal.api;

import java.util.UUID;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.util.math.Vec3d;

/**
 * Event hooks for SapphicsAudioLib that other mods can use to react to audio events.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Listen for audio playback starting
 * SapphicsAudioEvents.AUDIO_STARTED.register((sessionId, soundEventId, position, volume) -> {
 *     System.out.println("Sound started: " + soundEventId);
 *     return true; // Allow playback, return false to cancel
 * });
 * 
 * // Listen for audio playback stopping
 * SapphicsAudioEvents.AUDIO_STOPPED.register((sessionId) -> {
 *     System.out.println("Sound stopped: " + sessionId);
 * });
 * }</pre>
 */
public final class SapphicsAudioEvents {
    
    private SapphicsAudioEvents() {} // Prevent instantiation
    
    /**
     * Event fired when an audio stream is about to start.
     * Listeners can return false to cancel the playback.
     */
    public static final Event<AudioStarting> AUDIO_STARTING = EventFactory.createArrayBacked(
            AudioStarting.class,
            listeners -> (sessionId, soundEventId, position, volume) -> {
                for (AudioStarting listener : listeners) {
                    if (!listener.onAudioStarting(sessionId, soundEventId, position, volume)) {
                        return false;
                    }
                }
                return true;
            }
    );
    
    /**
     * Event fired after an audio stream has started playing.
     */
    public static final Event<AudioStarted> AUDIO_STARTED = EventFactory.createArrayBacked(
            AudioStarted.class,
            listeners -> (sessionId, soundEventId, position, durationSeconds) -> {
                for (AudioStarted listener : listeners) {
                    listener.onAudioStarted(sessionId, soundEventId, position, durationSeconds);
                }
            }
    );
    
    /**
     * Event fired when an audio session stops (either naturally or killed).
     */
    public static final Event<AudioStopped> AUDIO_STOPPED = EventFactory.createArrayBacked(
            AudioStopped.class,
            listeners -> (sessionId, wasKilled) -> {
                for (AudioStopped listener : listeners) {
                    listener.onAudioStopped(sessionId, wasKilled);
                }
            }
    );
    
    /**
     * Event fired when an audio chunk is received from the network.
     * Useful for debugging or showing visual indicators.
     */
    public static final Event<ChunkReceived> CHUNK_RECEIVED = EventFactory.createArrayBacked(
            ChunkReceived.class,
            listeners -> (sessionId, chunkIndex, isLast, dataSize) -> {
                for (ChunkReceived listener : listeners) {
                    listener.onChunkReceived(sessionId, chunkIndex, isLast, dataSize);
                }
            }
    );
    
    /**
     * Event fired when audio is fully reassembled and about to be decoded.
     */
    public static final Event<AudioReassembled> AUDIO_REASSEMBLED = EventFactory.createArrayBacked(
            AudioReassembled.class,
            listeners -> (sessionId, totalSize, chunkCount) -> {
                for (AudioReassembled listener : listeners) {
                    listener.onAudioReassembled(sessionId, totalSize, chunkCount);
                }
            }
    );
    
    // ==================== CALLBACK INTERFACES ====================
    
    @FunctionalInterface
    public interface AudioStarting {
        /**
         * Called when audio is about to start.
         * 
         * @param sessionId The unique session ID
         * @param soundEventId The registered sound event identifier
         * @param position The world position of the sound
         * @param volume The initial volume
         * @return true to allow, false to cancel
         */
        boolean onAudioStarting(UUID sessionId, String soundEventId, Vec3d position, float volume);
    }
    
    @FunctionalInterface
    public interface AudioStarted {
        /**
         * Called after audio has started playing.
         * 
         * @param sessionId The unique session ID
         * @param soundEventId The registered sound event identifier
         * @param position The world position of the sound
         * @param durationSeconds The duration of the audio in seconds
         */
        void onAudioStarted(UUID sessionId, String soundEventId, Vec3d position, float durationSeconds);
    }
    
    @FunctionalInterface
    public interface AudioStopped {
        /**
         * Called when audio stops.
         * 
         * @param sessionId The unique session ID
         * @param wasKilled true if stopped via kill command, false if ended naturally
         */
        void onAudioStopped(UUID sessionId, boolean wasKilled);
    }
    
    @FunctionalInterface
    public interface ChunkReceived {
        /**
         * Called when an audio chunk is received from the network.
         * 
         * @param sessionId The session this chunk belongs to
         * @param chunkIndex The sequential index of this chunk
         * @param isLast Whether this is the final chunk
         * @param dataSize Size of the chunk data in bytes
         */
        void onChunkReceived(UUID sessionId, int chunkIndex, boolean isLast, int dataSize);
    }
    
    @FunctionalInterface
    public interface AudioReassembled {
        /**
         * Called when all chunks have been received and reassembled.
         * 
         * @param sessionId The session that was reassembled
         * @param totalSize Total size of the audio data in bytes
         * @param chunkCount Number of chunks that were assembled
         */
        void onAudioReassembled(UUID sessionId, int totalSize, int chunkCount);
    }
}
