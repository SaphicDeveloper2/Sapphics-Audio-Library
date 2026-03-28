package com.sapphic.sal.network;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.registry.SoundRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side network handler that registers packet types and routes audio data.
 * Acts as the relay - receives chunks from sender and broadcasts to nearby players.
 * 
 * <p>Supports late-joiner sync: caches all chunks for active sessions so players
 * who walk into range mid-stream receive all previous chunks and can play the
 * complete audio (just starting late).</p>
 */
public class NetworkHandler {
    
    /**
     * Cache of all chunks for each active session - needed for late-joiner sync.
     * Key: session UUID, Value: ordered list of all chunks received so far
     */
    private static final Map<UUID, List<AudioChunkPayload>> sessionChunkCache = new ConcurrentHashMap<>();
    
    /**
     * Track which players have received the session's chunks.
     * Key: session UUID, Value: set of player UUIDs who have the stream
     */
    private static final Map<UUID, Set<UUID>> playersInSession = new ConcurrentHashMap<>();
    
    /**
     * Maximum number of chunks to cache per session (prevents memory issues).
     * ~15KB per chunk * 200 chunks = ~3MB max per session
     */
    private static final int MAX_CACHED_CHUNKS_PER_SESSION = 200;
    
    /**
     * Maximum number of active sessions to cache (prevents memory issues).
     */
    private static final int MAX_CACHED_SESSIONS = 50;
    
    /**
     * Registers all network payloads and server-side handlers.
     * Called during mod initialization.
     */
    public static void register() {
        // Register payload types for both directions
        PayloadTypeRegistry.playC2S().register(AudioChunkPayload.ID, AudioChunkPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AudioChunkPayload.ID, AudioChunkPayload.CODEC);
        
        PayloadTypeRegistry.playC2S().register(AudioControlPayload.ID, AudioControlPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AudioControlPayload.ID, AudioControlPayload.CODEC);
        
        // Register radio command payload (server -> client only)
        PayloadTypeRegistry.playS2C().register(RadioCommandPayload.ID, RadioCommandPayload.CODEC);
        
        // Register server-side receivers
        ServerPlayNetworking.registerGlobalReceiver(AudioChunkPayload.ID, NetworkHandler::handleAudioChunk);
        ServerPlayNetworking.registerGlobalReceiver(AudioControlPayload.ID, NetworkHandler::handleAudioControl);
        
        Sapphicsaudiolib.LOGGER.info("SapphicsAudioLib network handlers registered");
    }
    
    /**
     * Handles incoming audio chunks from clients and relays to nearby players.
     * Implements late-joiner sync by caching all chunks and sending them to players
     * who join mid-stream.
     */
    private static void handleAudioChunk(AudioChunkPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayerEntity sender = context.player();
        ServerWorld world = sender.getServerWorld();
        UUID sessionId = payload.sessionId();
        
        // Validate the sound event is registered
        if (!SoundRegistry.isRegistered(payload.soundEventId())) {
            Sapphicsaudiolib.LOGGER.warn("Rejected unregistered sound event: {} from player {}", 
                    payload.soundEventId(), sender.getName().getString());
            return;
        }
        
        // Initialize session caching if this is a new session
        if (payload.chunkIndex() == 0) {
            // Check if we're at capacity
            if (sessionChunkCache.size() >= MAX_CACHED_SESSIONS) {
                Sapphicsaudiolib.LOGGER.warn("Audio session cache full, clearing oldest sessions");
                // Simple cleanup - just clear half the oldest sessions
                int toRemove = MAX_CACHED_SESSIONS / 2;
                sessionChunkCache.keySet().stream().limit(toRemove).toList()
                        .forEach(id -> {
                            sessionChunkCache.remove(id);
                            playersInSession.remove(id);
                        });
            }
            sessionChunkCache.put(sessionId, new java.util.ArrayList<>());
            playersInSession.put(sessionId, ConcurrentHashMap.newKeySet());
        }
        
        // Cache this chunk for late joiners (if not over limit)
        List<AudioChunkPayload> cachedChunks = sessionChunkCache.get(sessionId);
        if (cachedChunks != null && cachedChunks.size() < MAX_CACHED_CHUNKS_PER_SESSION) {
            cachedChunks.add(payload);
        }
        
        // Determine the source position for range checking
        Vec3d sourcePos;
        if (payload.sourceEntityId() >= 0) {
            Entity sourceEntity = world.getEntityById(payload.sourceEntityId());
            if (sourceEntity != null) {
                sourcePos = sourceEntity.getPos();
            } else {
                sourcePos = sender.getPos(); // Fallback to sender position
            }
        } else {
            sourcePos = new Vec3d(payload.posX(), payload.posY(), payload.posZ());
        }
        
        // Find all players within hearing range and in the same dimension
        double maxDistance = payload.maxDistance() > 0 ? payload.maxDistance() : 64.0;
        Box hearingBox = new Box(
                sourcePos.x - maxDistance, sourcePos.y - maxDistance, sourcePos.z - maxDistance,
                sourcePos.x + maxDistance, sourcePos.y + maxDistance, sourcePos.z + maxDistance
        );
        
        List<ServerPlayerEntity> nearbyPlayers = world.getEntitiesByClass(
                ServerPlayerEntity.class, hearingBox,
                player -> player.squaredDistanceTo(sourcePos) <= maxDistance * maxDistance
        );
        
        // Get tracking set for late-joiner sync
        Set<UUID> playersInThisSession = playersInSession.get(sessionId);
        
        // Relay chunks to all nearby players
        for (ServerPlayerEntity player : nearbyPlayers) {
            UUID playerId = player.getUuid();
            
            // Check if this is a late joiner
            if (playersInThisSession != null && cachedChunks != null) {
                if (playersInThisSession.add(playerId)) {
                    // New player joining this session!
                    if (cachedChunks.size() > 1) {
                        // Late joiner - send all previous chunks first
                        Sapphicsaudiolib.LOGGER.debug("Late-joiner sync: sending {} cached chunks to {} for session {}",
                                cachedChunks.size() - 1, player.getName().getString(), sessionId);
                        
                        // Send all cached chunks except the current one (which we'll send below)
                        for (int i = 0; i < cachedChunks.size() - 1; i++) {
                            ServerPlayNetworking.send(player, cachedChunks.get(i));
                        }
                    }
                }
            }
            
            // Send the current chunk
            ServerPlayNetworking.send(player, payload);
        }
        
        // Clean up cache when last chunk is sent
        if (payload.isLast()) {
            sessionChunkCache.remove(sessionId);
            playersInSession.remove(sessionId);
        }
        
        if (payload.chunkIndex() == 0) {
            Sapphicsaudiolib.LOGGER.debug("Relaying audio session {} from {} to {} players",
                    sessionId, sender.getName().getString(), nearbyPlayers.size());
        }
    }
    
    /**
     * Handles audio control commands and relays to nearby players.
     */
    private static void handleAudioControl(AudioControlPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayerEntity sender = context.player();
        ServerWorld world = sender.getServerWorld();
        
        // For control packets, we broadcast to all players in the world
        // The client-side will ignore sessions they don't have
        for (ServerPlayerEntity player : world.getPlayers()) {
            ServerPlayNetworking.send(player, payload);
        }
        
        Sapphicsaudiolib.LOGGER.debug("Relayed audio control {} for session {} from {}",
                payload.type(), payload.sessionId(), sender.getName().getString());
        
        // Clean up chunk cache if session is stopped
        if (payload.type() == AudioControlPayload.ControlType.STOP) {
            sessionChunkCache.remove(payload.sessionId());
            playersInSession.remove(payload.sessionId());
        }
    }
    
    /**
     * Cleans up stale session caches. Should be called periodically.
     * Sessions are considered stale if they've been cached for too long
     * without receiving a final chunk.
     */
    public static void cleanupStaleSessions() {
        // Simple cleanup - just clear if there are too many cached sessions
        // This prevents memory leaks from abandoned streams
        if (sessionChunkCache.size() > MAX_CACHED_SESSIONS) {
            Sapphicsaudiolib.LOGGER.warn("Clearing {} stale audio session caches", sessionChunkCache.size());
            sessionChunkCache.clear();
            playersInSession.clear();
        }
    }
    
    /**
     * Gets the number of active session caches.
     * Useful for debugging.
     */
    public static int getCachedSessionCount() {
        return sessionChunkCache.size();
    }
}
