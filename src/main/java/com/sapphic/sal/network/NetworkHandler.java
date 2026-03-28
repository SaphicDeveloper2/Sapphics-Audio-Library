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

import java.util.List;

/**
 * Server-side network handler that registers packet types and routes audio data.
 * Acts as the relay - receives chunks from sender and broadcasts to nearby players.
 */
public class NetworkHandler {
    
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
        
        // Register server-side receivers
        ServerPlayNetworking.registerGlobalReceiver(AudioChunkPayload.ID, NetworkHandler::handleAudioChunk);
        ServerPlayNetworking.registerGlobalReceiver(AudioControlPayload.ID, NetworkHandler::handleAudioControl);
        
        Sapphicsaudiolib.LOGGER.info("SapphicsAudioLib network handlers registered");
    }
    
    /**
     * Handles incoming audio chunks from clients and relays to nearby players.
     * The server acts purely as a router - it never decodes or stores the audio.
     */
    private static void handleAudioChunk(AudioChunkPayload payload, ServerPlayNetworking.Context context) {
        ServerPlayerEntity sender = context.player();
        ServerWorld world = sender.getServerWorld();
        
        // Validate the sound event is registered
        if (!SoundRegistry.isRegistered(payload.soundEventId())) {
            Sapphicsaudiolib.LOGGER.warn("Rejected unregistered sound event: {} from player {}", 
                    payload.soundEventId(), sender.getName().getString());
            return;
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
        
        // Relay the chunk to all nearby players (including sender for consistency)
        for (ServerPlayerEntity player : nearbyPlayers) {
            ServerPlayNetworking.send(player, payload);
        }
        
        if (payload.chunkIndex() == 0) {
            Sapphicsaudiolib.LOGGER.debug("Relaying audio session {} from {} to {} players",
                    payload.sessionId(), sender.getName().getString(), nearbyPlayers.size());
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
    }
}
