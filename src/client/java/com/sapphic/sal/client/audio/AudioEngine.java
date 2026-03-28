package com.sapphic.sal.client.audio;

import com.sapphic.sal.Sapphicsaudiolib;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAL-based 3D audio engine for playing decoded PCM audio.
 * Handles source creation, 3D positioning, and buffer management.
 */
public class AudioEngine {
    
    /**
     * Represents an active audio playback session.
     */
    public static class AudioSession {
        final UUID sessionId;
        final int sourceId;
        final int bufferId;
        final int sourceEntityId;
        double posX, posY, posZ;
        final float maxDistance;
        final long startTime;
        boolean stopped = false;
        
        AudioSession(UUID sessionId, int sourceId, int bufferId, int sourceEntityId,
                     double posX, double posY, double posZ, float maxDistance) {
            this.sessionId = sessionId;
            this.sourceId = sourceId;
            this.bufferId = bufferId;
            this.sourceEntityId = sourceEntityId;
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.maxDistance = maxDistance;
            this.startTime = System.currentTimeMillis();
        }
        
        /**
         * @return Whether the OpenAL source has finished playing
         */
        public boolean isFinished() {
            if (stopped) return true;
            int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
            return state == AL10.AL_STOPPED || state == AL10.AL_INITIAL;
        }
        
        /**
         * @return Duration this session has been active in milliseconds
         */
        public long getActiveDuration() {
            return System.currentTimeMillis() - startTime;
        }
    }
    
    // Active playback sessions
    private static final Map<UUID, AudioSession> activeSessions = new ConcurrentHashMap<>();
    
    // Listener for session lifecycle events
    private static AudioSessionListener sessionListener = null;
    
    /**
     * Plays decoded audio through OpenAL with 3D positioning.
     * 
     * @param sessionId Unique session identifier
     * @param audioData Raw .ogg file data
     * @param sourceEntityId Entity to track for position (-1 for static)
     * @param posX X position
     * @param posY Y position
     * @param posZ Z position
     * @param volume Volume multiplier (0.0 - 1.0)
     * @param pitch Pitch multiplier
     * @param maxDistance Maximum hearing distance in blocks
     */
    public static void play(UUID sessionId, byte[] audioData, int sourceEntityId,
                            double posX, double posY, double posZ,
                            float volume, float pitch, float maxDistance) {
        
        // Check if session already exists (shouldn't happen, but safety check)
        if (activeSessions.containsKey(sessionId)) {
            Sapphicsaudiolib.LOGGER.warn("Session {} already exists, stopping old one", sessionId);
            stop(sessionId);
        }
        
        // Decode the audio
        AudioDecoder.DecodedAudio decoded;
        try {
            decoded = AudioDecoder.decode(audioData);
        } catch (AudioDecoder.AudioDecoderException e) {
            Sapphicsaudiolib.LOGGER.error("Failed to decode audio for session {}: {}", sessionId, e.getMessage());
            return;
        }
        
        try {
            // Create OpenAL buffer
            int bufferId = AL10.alGenBuffers();
            checkALError("alGenBuffers");
            
            // Upload PCM data to buffer
            AL10.alBufferData(bufferId, decoded.getOpenALFormat(), decoded.getPcmData(), decoded.getSampleRate());
            checkALError("alBufferData");
            
            // Create OpenAL source
            int sourceId = AL10.alGenSources();
            checkALError("alGenSources");
            
            // Attach buffer to source
            AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
            checkALError("alSourcei buffer");
            
            // Configure source properties
            AL10.alSourcef(sourceId, AL10.AL_GAIN, clampVolume(volume));
            AL10.alSourcef(sourceId, AL10.AL_PITCH, clampPitch(pitch));
            
            // Set 3D position
            if (sourceEntityId >= 0) {
                // Get entity current position
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.world != null) {
                    Entity entity = client.world.getEntityById(sourceEntityId);
                    if (entity != null) {
                        posX = entity.getX();
                        posY = entity.getY();
                        posZ = entity.getZ();
                    }
                }
            }
            AL10.alSource3f(sourceId, AL10.AL_POSITION, (float) posX, (float) posY, (float) posZ);
            checkALError("alSource3f position");
            
            // Configure 3D sound attenuation
            AL10.alSourcef(sourceId, AL10.AL_REFERENCE_DISTANCE, 1.0f);
            AL10.alSourcef(sourceId, AL10.AL_MAX_DISTANCE, maxDistance > 0 ? maxDistance : 64.0f);
            AL10.alSourcef(sourceId, AL10.AL_ROLLOFF_FACTOR, 1.0f);
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            checkALError("alSource distance config");
            
            // Create session record
            AudioSession session = new AudioSession(sessionId, sourceId, bufferId, sourceEntityId,
                                                     posX, posY, posZ, maxDistance);
            activeSessions.put(sessionId, session);
            
            // Start playback
            AL10.alSourcePlay(sourceId);
            checkALError("alSourcePlay");
            
            Sapphicsaudiolib.LOGGER.debug("Started playback for session {} (buffer={}, source={}, duration={}s)",
                    sessionId, bufferId, sourceId, decoded.getDurationSeconds());
            
            // Notify listener
            if (sessionListener != null) {
                sessionListener.onSessionStarted(sessionId, decoded.getDurationSeconds());
            }
            
        } finally {
            // Free decoded audio (we've uploaded to OpenAL buffer)
            decoded.free();
        }
    }
    
    /**
     * Stops and cleans up a specific session.
     * 
     * @param sessionId The session to stop
     * @return true if session existed and was stopped
     */
    public static boolean stop(UUID sessionId) {
        AudioSession session = activeSessions.remove(sessionId);
        if (session != null) {
            session.stopped = true;
            
            // Stop playback
            AL10.alSourceStop(session.sourceId);
            
            // Detach buffer from source
            AL10.alSourcei(session.sourceId, AL10.AL_BUFFER, 0);
            
            // Delete source and buffer
            AL10.alDeleteSources(session.sourceId);
            AL10.alDeleteBuffers(session.bufferId);
            
            Sapphicsaudiolib.LOGGER.debug("Stopped session {} (was active {}ms)", 
                    sessionId, session.getActiveDuration());
            
            // Notify listener
            if (sessionListener != null) {
                sessionListener.onSessionStopped(sessionId);
            }
            
            return true;
        }
        return false;
    }
    
    /**
     * Pauses playback of a session.
     * 
     * @param sessionId The session to pause
     */
    public static void pause(UUID sessionId) {
        AudioSession session = activeSessions.get(sessionId);
        if (session != null && !session.stopped) {
            AL10.alSourcePause(session.sourceId);
            Sapphicsaudiolib.LOGGER.debug("Paused session {}", sessionId);
        }
    }
    
    /**
     * Resumes playback of a paused session.
     * 
     * @param sessionId The session to resume
     */
    public static void resume(UUID sessionId) {
        AudioSession session = activeSessions.get(sessionId);
        if (session != null && !session.stopped) {
            AL10.alSourcePlay(session.sourceId);
            Sapphicsaudiolib.LOGGER.debug("Resumed session {}", sessionId);
        }
    }
    
    /**
     * Sets the volume of an active session.
     * 
     * @param sessionId The session to adjust
     * @param volume New volume (0.0 - 1.0)
     */
    public static void setVolume(UUID sessionId, float volume) {
        AudioSession session = activeSessions.get(sessionId);
        if (session != null && !session.stopped) {
            AL10.alSourcef(session.sourceId, AL10.AL_GAIN, clampVolume(volume));
        }
    }
    
    /**
     * Sets the pitch of an active session.
     * 
     * @param sessionId The session to adjust
     * @param pitch New pitch multiplier
     */
    public static void setPitch(UUID sessionId, float pitch) {
        AudioSession session = activeSessions.get(sessionId);
        if (session != null && !session.stopped) {
            AL10.alSourcef(session.sourceId, AL10.AL_PITCH, clampPitch(pitch));
        }
    }
    
    /**
     * Sets the 3D position of an active session.
     * 
     * @param sessionId The session to adjust
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     */
    public static void setPosition(UUID sessionId, float x, float y, float z) {
        AudioSession session = activeSessions.get(sessionId);
        if (session != null && !session.stopped) {
            session.posX = x;
            session.posY = y;
            session.posZ = z;
            AL10.alSource3f(session.sourceId, AL10.AL_POSITION, x, y, z);
        }
    }
    
    /**
     * Sets the rolloff factor for distance attenuation.
     * 
     * @param sessionId The session to adjust
     * @param rolloff Rolloff factor (0 = no attenuation, 1 = default, higher = faster falloff)
     */
    public static void setRolloff(UUID sessionId, float rolloff) {
        AudioSession session = activeSessions.get(sessionId);
        if (session != null && !session.stopped) {
            AL10.alSourcef(session.sourceId, AL10.AL_ROLLOFF_FACTOR, Math.max(0, rolloff));
        }
    }
    
    /**
     * Updates entity-tracked sessions with current entity positions.
     * Should be called every tick.
     */
    public static void updateEntityPositions() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        
        for (AudioSession session : activeSessions.values()) {
            if (session.stopped || session.sourceEntityId < 0) continue;
            
            Entity entity = client.world.getEntityById(session.sourceEntityId);
            if (entity != null) {
                float x = (float) entity.getX();
                float y = (float) entity.getY();
                float z = (float) entity.getZ();
                
                // Only update if position changed
                if (x != session.posX || y != session.posY || z != session.posZ) {
                    session.posX = x;
                    session.posY = y;
                    session.posZ = z;
                    AL10.alSource3f(session.sourceId, AL10.AL_POSITION, x, y, z);
                }
            }
        }
    }
    
    /**
     * Checks if a session is currently active.
     * 
     * @param sessionId The session to check
     * @return true if the session exists and hasn't finished
     */
    public static boolean isPlaying(UUID sessionId) {
        AudioSession session = activeSessions.get(sessionId);
        if (session == null) return false;
        return !session.isFinished();
    }
    
    /**
     * Gets the number of currently active sessions.
     * 
     * @return Number of active playback sessions
     */
    public static int getActiveSessionCount() {
        return activeSessions.size();
    }
    
    /**
     * Stops all active sessions. Used during cleanup.
     */
    public static void stopAll() {
        for (UUID sessionId : activeSessions.keySet().toArray(new UUID[0])) {
            stop(sessionId);
        }
    }
    
    /**
     * Sets a listener for session lifecycle events.
     * 
     * @param listener The listener to set (null to remove)
     */
    public static void setSessionListener(AudioSessionListener listener) {
        sessionListener = listener;
    }
    
    /**
     * Listener interface for audio session lifecycle events.
     */
    public interface AudioSessionListener {
        void onSessionStarted(UUID sessionId, float durationSeconds);
        void onSessionStopped(UUID sessionId);
    }
    
    /**
     * Gets all active session IDs.
     */
    public static UUID[] getActiveSessionIds() {
        return activeSessions.keySet().toArray(new UUID[0]);
    }
    
    /**
     * Gets session info for debugging.
     */
    public static AudioSession getSession(UUID sessionId) {
        return activeSessions.get(sessionId);
    }
    
    // Helper methods
    
    private static float clampVolume(float volume) {
        return Math.max(0.0f, Math.min(1.0f, volume));
    }
    
    private static float clampPitch(float pitch) {
        return Math.max(0.5f, Math.min(2.0f, pitch)); // OpenAL reasonable range
    }
    
    private static void checkALError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            Sapphicsaudiolib.LOGGER.error("OpenAL error during {}: {}", operation, getALErrorString(error));
        }
    }
    
    private static String getALErrorString(int error) {
        return switch (error) {
            case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
            default -> "Unknown error: " + error;
        };
    }
}
