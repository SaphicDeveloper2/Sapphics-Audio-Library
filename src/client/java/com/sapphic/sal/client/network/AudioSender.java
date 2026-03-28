package com.sapphic.sal.client.network;

import com.sapphic.sal.network.AudioChunkPayload;
import com.sapphic.sal.network.AudioControlPayload;
import com.sapphic.sal.registry.SoundRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.entity.Entity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side audio sender that reads local .ogg files, chunks them,
 * and sends them to the server for relay to nearby players.
 */
public class AudioSender {
    
    // Thread pool for async file reading and sending
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SapphicsAudioLib-Sender");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Streams an audio file from disk, attaching it to an entity for 3D positioning.
     * 
     * @param audioPath Path to the local .ogg file
     * @param sourceEntity The entity to attach the sound source to
     * @param soundEventId Registered sound event identifier for validation
     * @param volume Initial volume (0.0 - 1.0)
     * @param pitch Initial pitch multiplier
     * @param maxDistance Maximum hearing distance in blocks
     * @return CompletableFuture containing the session UUID, or failed if error
     */
    public static CompletableFuture<UUID> streamFromEntity(Path audioPath, Entity sourceEntity,
                                                            String soundEventId, float volume,
                                                            float pitch, float maxDistance) {
        return streamInternal(audioPath, sourceEntity.getId(), 0, 0, 0, 
                              soundEventId, volume, pitch, maxDistance);
    }
    
    /**
     * Streams an audio file from disk at a static world position.
     * 
     * @param audioPath Path to the local .ogg file
     * @param x X coordinate in world
     * @param y Y coordinate in world
     * @param z Z coordinate in world
     * @param soundEventId Registered sound event identifier for validation
     * @param volume Initial volume (0.0 - 1.0)
     * @param pitch Initial pitch multiplier
     * @param maxDistance Maximum hearing distance in blocks
     * @return CompletableFuture containing the session UUID, or failed if error
     */
    public static CompletableFuture<UUID> streamFromPosition(Path audioPath, double x, double y, double z,
                                                              String soundEventId, float volume,
                                                              float pitch, float maxDistance) {
        return streamInternal(audioPath, -1, x, y, z, soundEventId, volume, pitch, maxDistance);
    }
    
    /**
     * Streams audio data from memory (already loaded bytes).
     * 
     * @param audioData The .ogg file data in memory
     * @param sourceEntityId Entity ID for attachment (-1 for static position)
     * @param x X coordinate (used when entityId is -1)
     * @param y Y coordinate (used when entityId is -1)
     * @param z Z coordinate (used when entityId is -1)
     * @param soundEventId Registered sound event identifier
     * @param volume Initial volume
     * @param pitch Initial pitch
     * @param maxDistance Maximum hearing distance
     * @return CompletableFuture containing the session UUID
     */
    public static CompletableFuture<UUID> streamFromMemory(byte[] audioData, int sourceEntityId,
                                                            double x, double y, double z,
                                                            String soundEventId, float volume,
                                                            float pitch, float maxDistance) {
        return CompletableFuture.supplyAsync(() -> {
            validateRequest(soundEventId);
            
            UUID sessionId = UUID.randomUUID();
            sendChunkedData(sessionId, audioData, sourceEntityId, x, y, z, 
                           soundEventId, volume, pitch, maxDistance);
            return sessionId;
        }, EXECUTOR);
    }
    
    /**
     * Internal method to handle streaming from file path.
     */
    private static CompletableFuture<UUID> streamInternal(Path audioPath, int entityId,
                                                           double x, double y, double z,
                                                           String soundEventId, float volume,
                                                           float pitch, float maxDistance) {
        return CompletableFuture.supplyAsync(() -> {
            validateRequest(soundEventId);
            
            // Read the entire file into memory
            byte[] audioData;
            try {
                audioData = Files.readAllBytes(audioPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read audio file: " + audioPath, e);
            }
            
            UUID sessionId = UUID.randomUUID();
            sendChunkedData(sessionId, audioData, entityId, x, y, z, 
                           soundEventId, volume, pitch, maxDistance);
            return sessionId;
        }, EXECUTOR);
    }
    
    /**
     * Validates the streaming request before processing.
     */
    private static void validateRequest(String soundEventId) {
        if (!SoundRegistry.isRegistered(soundEventId)) {
            throw new IllegalArgumentException("Sound event not registered: " + soundEventId);
        }
    }
    
    /**
     * Chunks the audio data and sends each piece as a network packet.
     */
    private static void sendChunkedData(UUID sessionId, byte[] audioData, int entityId,
                                         double x, double y, double z, String soundEventId,
                                         float volume, float pitch, float maxDistance) {
        int chunkSize = AudioChunkPayload.MAX_CHUNK_SIZE;
        int totalChunks = (int) Math.ceil((double) audioData.length / chunkSize);
        
        for (int i = 0; i < totalChunks; i++) {
            int offset = i * chunkSize;
            int length = Math.min(chunkSize, audioData.length - offset);
            byte[] chunkData = Arrays.copyOfRange(audioData, offset, offset + length);
            
            boolean isLast = (i == totalChunks - 1);
            
            AudioChunkPayload payload;
            if (entityId >= 0) {
                payload = AudioChunkPayload.forEntity(sessionId, entityId, i, isLast, chunkData,
                                                      soundEventId, volume, pitch, maxDistance);
            } else {
                payload = AudioChunkPayload.forPosition(sessionId, x, y, z, i, isLast, chunkData,
                                                        soundEventId, volume, pitch, maxDistance);
            }
            
            // Send the chunk to server
            ClientPlayNetworking.send(payload);
            
            // Small delay between chunks to avoid overwhelming the network
            if (!isLast) {
                try {
                    Thread.sleep(1); // 1ms between chunks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    /**
     * Sends a control packet to stop an active sound session.
     * 
     * @param sessionId The session to stop
     */
    public static void stopSession(UUID sessionId) {
        ClientPlayNetworking.send(AudioControlPayload.stop(sessionId));
    }
    
    /**
     * Sends a control packet to adjust volume of an active session.
     * 
     * @param sessionId The session to adjust
     * @param volume New volume (0.0 - 1.0)
     */
    public static void setVolume(UUID sessionId, float volume) {
        ClientPlayNetworking.send(AudioControlPayload.setVolume(sessionId, volume));
    }
    
    /**
     * Sends a control packet to adjust pitch of an active session.
     * 
     * @param sessionId The session to adjust
     * @param pitch New pitch multiplier
     */
    public static void setPitch(UUID sessionId, float pitch) {
        ClientPlayNetworking.send(AudioControlPayload.setPitch(sessionId, pitch));
    }
    
    /**
     * Sends a control packet to pause an active session.
     * 
     * @param sessionId The session to pause
     */
    public static void pauseSession(UUID sessionId) {
        ClientPlayNetworking.send(AudioControlPayload.pause(sessionId));
    }
    
    /**
     * Sends a control packet to resume a paused session.
     * 
     * @param sessionId The session to resume
     */
    public static void resumeSession(UUID sessionId) {
        ClientPlayNetworking.send(AudioControlPayload.resume(sessionId));
    }
    
    /**
     * Shuts down the sender's thread pool. Call during mod unload.
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}
