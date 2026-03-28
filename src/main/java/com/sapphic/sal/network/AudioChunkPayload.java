package com.sapphic.sal.network;

import java.util.UUID;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payload for streaming audio chunks between clients via server.
 * Contains metadata for proper reassembly and 3D positioning.
 */
public record AudioChunkPayload(
        UUID sessionId,          // Unique identifier for this sound playback session
        int sourceEntityId,      // Entity ID for 3D positioning (-1 for static position)
        double posX,             // X coordinate (used when sourceEntityId is -1)
        double posY,             // Y coordinate (used when sourceEntityId is -1)
        double posZ,             // Z coordinate (used when sourceEntityId is -1)
        int chunkIndex,          // Sequential index for ordering chunks
        boolean isLast,          // Flag indicating this is the final chunk
        byte[] audioData,        // Raw audio data chunk (max ~15KB to stay under packet limits)
        String soundEventId,     // Registered sound event identifier for validation
        float volume,            // Initial volume (0.0 - 1.0)
        float pitch,             // Initial pitch multiplier
        float maxDistance        // Maximum hearing distance in blocks
) implements CustomPayload {
    
    public static final int MAX_CHUNK_SIZE = 15000; // ~15KB per chunk to stay under 32KB packet limit
    
    public static final Id<AudioChunkPayload> ID = new Id<>(Identifier.of("sapphicsaudiolib", "audio_chunk"));
    
    // Custom codec since tuple() only supports up to 6 fields
    public static final PacketCodec<RegistryByteBuf, AudioChunkPayload> CODEC = new PacketCodec<>() {
        @Override
        public AudioChunkPayload decode(RegistryByteBuf buf) {
            UUID sessionId = UUID.fromString(buf.readString());
            int sourceEntityId = buf.readInt();
            double posX = buf.readDouble();
            double posY = buf.readDouble();
            double posZ = buf.readDouble();
            int chunkIndex = buf.readInt();
            boolean isLast = buf.readBoolean();
            byte[] audioData = buf.readByteArray();
            String soundEventId = buf.readString();
            float volume = buf.readFloat();
            float pitch = buf.readFloat();
            float maxDistance = buf.readFloat();
            return new AudioChunkPayload(sessionId, sourceEntityId, posX, posY, posZ,
                    chunkIndex, isLast, audioData, soundEventId, volume, pitch, maxDistance);
        }
        
        @Override
        public void encode(RegistryByteBuf buf, AudioChunkPayload payload) {
            buf.writeString(payload.sessionId().toString());
            buf.writeInt(payload.sourceEntityId());
            buf.writeDouble(payload.posX());
            buf.writeDouble(payload.posY());
            buf.writeDouble(payload.posZ());
            buf.writeInt(payload.chunkIndex());
            buf.writeBoolean(payload.isLast());
            buf.writeByteArray(payload.audioData());
            buf.writeString(payload.soundEventId());
            buf.writeFloat(payload.volume());
            buf.writeFloat(payload.pitch());
            buf.writeFloat(payload.maxDistance());
        }
    };
    
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
    
    /**
     * Creates a payload for entity-attached sound
     */
    public static AudioChunkPayload forEntity(UUID sessionId, int entityId, int chunkIndex, 
                                               boolean isLast, byte[] data, String soundEventId,
                                               float volume, float pitch, float maxDistance) {
        return new AudioChunkPayload(sessionId, entityId, 0, 0, 0, chunkIndex, isLast, 
                                     data, soundEventId, volume, pitch, maxDistance);
    }
    
    /**
     * Creates a payload for static position sound
     */
    public static AudioChunkPayload forPosition(UUID sessionId, double x, double y, double z,
                                                 int chunkIndex, boolean isLast, byte[] data,
                                                 String soundEventId, float volume, float pitch, 
                                                 float maxDistance) {
        return new AudioChunkPayload(sessionId, -1, x, y, z, chunkIndex, isLast, 
                                     data, soundEventId, volume, pitch, maxDistance);
    }
}
