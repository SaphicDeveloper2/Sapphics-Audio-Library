package com.sapphic.sal.client.api;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.sapphic.sal.client.audio.AudioDecoder;
import com.sapphic.sal.client.audio.AudioEngine;
import com.sapphic.sal.client.audio.MemoryCustodian;
import com.sapphic.sal.client.network.AudioSender;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * Client-side API for SapphicsAudioLib - Playback and audio control methods.
 * 
 * <p>This API is CLIENT-SIDE ONLY. For sound registration, use {@code SapphicsAudioAPI}.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Play a sound attached to an entity
 * Path audioFile = Path.of("path/to/sound.ogg");
 * CompletableFuture<AudioSession> session = SapphicsAudioClientAPI.playFromEntity(
 *     audioFile,
 *     targetEntity,
 *     "mymod:tardis_demat",
 *     SoundOptions.defaults()
 * );
 * 
 * // Control the sound afterwards
 * session.thenAccept(s -> {
 *     s.setVolume(0.5f);
 *     s.setPitch(1.2f);
 * });
 * 
 * // Stop the sound
 * session.thenAccept(s -> s.stop());
 * }</pre>
 */
public final class SapphicsAudioClientAPI {
    
    private SapphicsAudioClientAPI() {} // Prevent instantiation
    
    // ==================== PLAYBACK - ENTITY ATTACHED ====================
    
    /**
     * Plays an audio file attached to an entity for 3D positioning.
     * The sound will follow the entity as it moves.
     * 
     * @param audioPath Path to the local .ogg file
     * @param sourceEntity Entity to attach the sound to
     * @param soundEventId Registered sound event identifier
     * @param options Sound configuration options
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playFromEntity(Path audioPath, Entity sourceEntity,
                                                                  String soundEventId, SoundOptions options) {
        return AudioSender.streamFromEntity(
                audioPath, sourceEntity, soundEventId,
                options.volume(), options.pitch(), options.maxDistance()
        ).thenApply(AudioSession::new);
    }
    
    /**
     * Plays an audio file attached to an entity with default options.
     * 
     * @param audioPath Path to the local .ogg file
     * @param sourceEntity Entity to attach the sound to
     * @param soundEventId Registered sound event identifier
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playFromEntity(Path audioPath, Entity sourceEntity,
                                                                  String soundEventId) {
        return playFromEntity(audioPath, sourceEntity, soundEventId, SoundOptions.defaults());
    }
    
    /**
     * Plays audio data from memory attached to an entity.
     * 
     * @param audioData Raw .ogg file data
     * @param sourceEntity Entity to attach the sound to
     * @param soundEventId Registered sound event identifier
     * @param options Sound configuration options
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playFromEntityMemory(byte[] audioData, Entity sourceEntity,
                                                                        String soundEventId, SoundOptions options) {
        return AudioSender.streamFromMemory(
                audioData, sourceEntity.getId(), 0, 0, 0,
                soundEventId, options.volume(), options.pitch(), options.maxDistance()
        ).thenApply(AudioSession::new);
    }
    
    // ==================== PLAYBACK - STATIC POSITION ====================
    
    /**
     * Plays an audio file at a static world position.
     * 
     * @param audioPath Path to the local .ogg file
     * @param position World position for the sound
     * @param soundEventId Registered sound event identifier
     * @param options Sound configuration options
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playAtPosition(Path audioPath, Vec3d position,
                                                                  String soundEventId, SoundOptions options) {
        return AudioSender.streamFromPosition(
                audioPath, position.x, position.y, position.z,
                soundEventId, options.volume(), options.pitch(), options.maxDistance()
        ).thenApply(AudioSession::new);
    }
    
    /**
     * Plays an audio file at a static world position with default options.
     * 
     * @param audioPath Path to the local .ogg file
     * @param position World position for the sound
     * @param soundEventId Registered sound event identifier
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playAtPosition(Path audioPath, Vec3d position,
                                                                  String soundEventId) {
        return playAtPosition(audioPath, position, soundEventId, SoundOptions.defaults());
    }
    
    /**
     * Plays an audio file at explicit coordinates.
     * 
     * @param audioPath Path to the local .ogg file
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param soundEventId Registered sound event identifier
     * @param options Sound configuration options
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playAtPosition(Path audioPath, double x, double y, double z,
                                                                  String soundEventId, SoundOptions options) {
        return AudioSender.streamFromPosition(
                audioPath, x, y, z,
                soundEventId, options.volume(), options.pitch(), options.maxDistance()
        ).thenApply(AudioSession::new);
    }
    
    /**
     * Plays audio data from memory at a static position.
     * 
     * @param audioData Raw .ogg file data
     * @param position World position
     * @param soundEventId Registered sound event identifier
     * @param options Sound configuration options
     * @return CompletableFuture resolving to the AudioSession handle
     */
    public static CompletableFuture<AudioSession> playAtPositionMemory(byte[] audioData, Vec3d position,
                                                                        String soundEventId, SoundOptions options) {
        return AudioSender.streamFromMemory(
                audioData, -1, position.x, position.y, position.z,
                soundEventId, options.volume(), options.pitch(), options.maxDistance()
        ).thenApply(AudioSession::new);
    }
    
    // ==================== LOCAL PLAYBACK (NO NETWORKING) ====================
    
    /**
     * Plays audio locally only (no network streaming to other players).
     * Useful for UI sounds or client-only effects.
     * 
     * @param audioData Raw .ogg file data
     * @param position World position for 3D audio
     * @param volume Volume (0.0 - 1.0)
     * @param pitch Pitch multiplier
     * @param maxDistance Maximum hearing distance
     * @return Session UUID, or null if playback failed
     */
    public static UUID playLocal(byte[] audioData, Vec3d position, 
                                  float volume, float pitch, float maxDistance) {
        UUID sessionId = UUID.randomUUID();
        AudioEngine.play(sessionId, audioData, -1, position.x, position.y, position.z,
                        volume, pitch, maxDistance);
        return AudioEngine.isPlaying(sessionId) ? sessionId : null;
    }
    
    /**
     * Plays audio locally attached to coordinates.
     */
    public static UUID playLocal(byte[] audioData, double x, double y, double z,
                                  float volume, float pitch, float maxDistance) {
        return playLocal(audioData, new Vec3d(x, y, z), volume, pitch, maxDistance);
    }
    
    // ==================== SESSION CONTROL ====================
    
    /**
     * Stops a sound session by its UUID.
     * This is the kill switch for immediately halting playback.
     * 
     * @param sessionId The session UUID to stop
     */
    public static void stopSession(UUID sessionId) {
        AudioSender.stopSession(sessionId);
    }
    
    /**
     * Checks if a session is currently playing.
     * 
     * @param sessionId The session to check
     * @return true if actively playing
     */
    public static boolean isPlaying(UUID sessionId) {
        return AudioEngine.isPlaying(sessionId);
    }
    
    /**
     * Gets the total number of active audio sessions.
     * 
     * @return Number of actively playing sessions
     */
    public static int getActiveSessionCount() {
        return AudioEngine.getActiveSessionCount();
    }
    
    /**
     * Stops all active audio sessions.
     * Use with caution - this kills ALL custom sounds.
     */
    public static void stopAllSessions() {
        AudioEngine.stopAll();
    }
    
    // ==================== AUDIO ANALYSIS ====================
    
    /**
     * Gets information about an audio file without playing it.
     * 
     * @param oggData Raw .ogg file data
     * @return AudioInfo with metadata, or null if parsing failed
     */
    public static AudioInfo getAudioInfo(byte[] oggData) {
        try {
            AudioDecoder.AudioInfo info = AudioDecoder.getInfo(oggData);
            return new AudioInfo(info.sampleRate(), info.channels(), 
                               info.totalSamples(), info.durationSeconds());
        } catch (AudioDecoder.AudioDecoderException e) {
            return null;
        }
    }
    
    // ==================== ENGINE STATUS ====================
    
    /**
     * Gets the number of pending (incomplete) audio sessions.
     * High numbers may indicate network issues.
     */
    public static int getPendingSessionCount() {
        return MemoryCustodian.getPendingSessionCount();
    }
    
    /**
     * Gets total sessions cleaned up since startup.
     */
    public static long getTotalSessionsCleaned() {
        return MemoryCustodian.getTotalSessionsCleaned();
    }
    
    /**
     * Forces immediate cleanup of all audio resources.
     * Call this when leaving a world or during error recovery.
     */
    public static void forceCleanup() {
        MemoryCustodian.forceCleanup();
    }
    
    // ==================== INNER CLASSES ====================
    
    /**
     * Audio file information.
     */
    public record AudioInfo(int sampleRate, int channels, int totalSamples, float durationSeconds) {
        public boolean isMono() { return channels == 1; }
        public boolean isStereo() { return channels == 2; }
    }
    
    /**
     * Handle for controlling an active audio session.
     * Provides methods to adjust playback properties or stop the sound.
     */
    public static class AudioSession {
        private final UUID sessionId;
        
        AudioSession(UUID sessionId) {
            this.sessionId = sessionId;
        }
        
        /**
         * @return The unique session identifier
         */
        public UUID getSessionId() {
            return sessionId;
        }
        
        /**
         * Stops this sound immediately (kill switch).
         */
        public void stop() {
            AudioSender.stopSession(sessionId);
        }
        
        /**
         * Pauses playback of this sound.
         */
        public void pause() {
            AudioSender.pauseSession(sessionId);
        }
        
        /**
         * Resumes playback if paused.
         */
        public void resume() {
            AudioSender.resumeSession(sessionId);
        }
        
        /**
         * Sets the volume of this sound.
         * 
         * @param volume Volume level (0.0 to 1.0)
         */
        public void setVolume(float volume) {
            AudioSender.setVolume(sessionId, volume);
        }
        
        /**
         * Sets the pitch of this sound.
         * 
         * @param pitch Pitch multiplier (0.5 to 2.0)
         */
        public void setPitch(float pitch) {
            AudioSender.setPitch(sessionId, pitch);
        }
        
        /**
         * Checks if this session is still playing.
         * 
         * @return true if actively playing
         */
        public boolean isPlaying() {
            return AudioEngine.isPlaying(sessionId);
        }
    }
    
    /**
     * Configuration options for sound playback.
     */
    public record SoundOptions(float volume, float pitch, float maxDistance) {
        
        /**
         * Default sound options (full volume, normal pitch, 64 block range).
         */
        public static SoundOptions defaults() {
            return new SoundOptions(1.0f, 1.0f, 64.0f);
        }
        
        /**
         * Creates options with specified volume.
         */
        public static SoundOptions withVolume(float volume) {
            return new SoundOptions(volume, 1.0f, 64.0f);
        }
        
        /**
         * Builder for creating custom options.
         */
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private float volume = 1.0f;
            private float pitch = 1.0f;
            private float maxDistance = 64.0f;
            
            public Builder volume(float volume) {
                this.volume = Math.max(0, Math.min(1, volume));
                return this;
            }
            
            public Builder pitch(float pitch) {
                this.pitch = Math.max(0.5f, Math.min(2.0f, pitch));
                return this;
            }
            
            public Builder maxDistance(float distance) {
                this.maxDistance = Math.max(1, distance);
                return this;
            }
            
            public SoundOptions build() {
                return new SoundOptions(volume, pitch, maxDistance);
            }
        }
    }
}
