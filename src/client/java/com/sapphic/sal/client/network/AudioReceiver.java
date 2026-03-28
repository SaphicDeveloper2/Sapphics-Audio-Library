package com.sapphic.sal.client.network;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.AudioEngine;
import com.sapphic.sal.network.AudioChunkPayload;
import com.sapphic.sal.network.AudioControlPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side receiver that collects incoming audio chunks, reassembles them,
 * and passes the complete audio data to the audio engine for playback.
 */
public class AudioReceiver {
    
    /**
     * Holds chunks for an in-progress audio stream session.
     */
    private static class PendingSession {
        final UUID sessionId;
        final int sourceEntityId;
        final double posX, posY, posZ;
        final String soundEventId;
        final float volume;
        final float pitch;
        final float maxDistance;
        final Map<Integer, byte[]> chunks = new TreeMap<>(); // TreeMap keeps chunks ordered
        int expectedLastIndex = -1;
        long creationTime;
        
        PendingSession(AudioChunkPayload firstChunk) {
            this.sessionId = firstChunk.sessionId();
            this.sourceEntityId = firstChunk.sourceEntityId();
            this.posX = firstChunk.posX();
            this.posY = firstChunk.posY();
            this.posZ = firstChunk.posZ();
            this.soundEventId = firstChunk.soundEventId();
            this.volume = firstChunk.volume();
            this.pitch = firstChunk.pitch();
            this.maxDistance = firstChunk.maxDistance();
            this.creationTime = System.currentTimeMillis();
        }
        
        void addChunk(int index, byte[] data, boolean isLast) {
            chunks.put(index, data);
            if (isLast) {
                expectedLastIndex = index;
            }
        }
        
        boolean isComplete() {
            if (expectedLastIndex < 0) return false;
            // Check we have all chunks from 0 to expectedLastIndex
            for (int i = 0; i <= expectedLastIndex; i++) {
                if (!chunks.containsKey(i)) return false;
            }
            return true;
        }
        
        byte[] reassemble() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i <= expectedLastIndex; i++) {
                byte[] chunk = chunks.get(i);
                baos.write(chunk, 0, chunk.length);
            }
            return baos.toByteArray();
        }
        
        boolean isExpired() {
            // Sessions expire after 30 seconds of no completion
            return System.currentTimeMillis() - creationTime > 30000;
        }
    }
    
    // Active pending sessions waiting for completion
    private static final Map<UUID, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    
    /**
     * Registers the client-side packet receivers.
     * Called during client mod initialization.
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(AudioChunkPayload.ID, AudioReceiver::handleAudioChunk);
        ClientPlayNetworking.registerGlobalReceiver(AudioControlPayload.ID, AudioReceiver::handleAudioControl);
        
        Sapphicsaudiolib.LOGGER.info("SapphicsAudioLib client receivers registered");
    }
    
    /**
     * Handles incoming audio chunk packets.
     */
    private static void handleAudioChunk(AudioChunkPayload payload, ClientPlayNetworking.Context context) {
        UUID sessionId = payload.sessionId();
        
        // Get or create pending session
        PendingSession session = pendingSessions.computeIfAbsent(sessionId, 
                k -> new PendingSession(payload));
        
        // Add this chunk
        session.addChunk(payload.chunkIndex(), payload.audioData(), payload.isLast());
        
        // Check if session is complete
        if (session.isComplete()) {
            pendingSessions.remove(sessionId);
            
            // Reassemble the audio data
            byte[] audioData = session.reassemble();
            
            Sapphicsaudiolib.LOGGER.debug("Reassembled audio session {} ({} bytes, {} chunks)",
                    sessionId, audioData.length, session.expectedLastIndex + 1);
            
            // Pass to audio engine for playback - schedule on main thread
            context.client().execute(() -> {
                AudioEngine.play(
                        sessionId,
                        audioData,
                        session.sourceEntityId,
                        session.posX, session.posY, session.posZ,
                        session.volume, session.pitch, session.maxDistance
                );
            });
        }
    }
    
    /**
     * Handles audio control packets (stop, volume, pitch, etc.)
     */
    private static void handleAudioControl(AudioControlPayload payload, ClientPlayNetworking.Context context) {
        UUID sessionId = payload.sessionId();
        
        context.client().execute(() -> {
            switch (payload.type()) {
                case STOP -> {
                    // Remove any pending session
                    pendingSessions.remove(sessionId);
                    // Stop active playback
                    AudioEngine.stop(sessionId);
                }
                case SET_VOLUME -> AudioEngine.setVolume(sessionId, payload.value1());
                case SET_PITCH -> AudioEngine.setPitch(sessionId, payload.value1());
                case SET_POSITION -> AudioEngine.setPosition(sessionId, 
                        payload.value1(), payload.value2(), payload.value3());
                case PAUSE -> AudioEngine.pause(sessionId);
                case RESUME -> AudioEngine.resume(sessionId);
            }
        });
    }
    
    /**
     * Cleans up expired pending sessions.
     * Should be called periodically (e.g., every tick or every few seconds).
     */
    public static void cleanupExpiredSessions() {
        pendingSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                Sapphicsaudiolib.LOGGER.warn("Audio session {} expired without completing", entry.getKey());
                return true;
            }
            return false;
        });
    }
    
    /**
     * Gets the number of pending sessions (waiting for completion).
     * Useful for debugging.
     */
    public static int getPendingSessionCount() {
        return pendingSessions.size();
    }
    
    /**
     * Clears all pending sessions. Used during cleanup.
     */
    public static void clearAll() {
        pendingSessions.clear();
    }
}
