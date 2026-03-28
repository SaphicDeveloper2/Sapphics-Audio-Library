package com.sapphic.sal.network;

import java.util.UUID;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payload for controlling active audio playback sessions.
 * Used for stopping sounds, adjusting volume/pitch dynamically.
 */
public record AudioControlPayload(
        UUID sessionId,      // The session to control
        ControlType type,    // Type of control action
        float value1,        // Primary value (volume, pitch, etc.)
        float value2,        // Secondary value (unused for most operations)
        float value3         // Tertiary value (unused for most operations)
) implements CustomPayload {
    
    public enum ControlType {
        STOP,           // Kill the sound immediately
        SET_VOLUME,     // Change volume (value1 = new volume)
        SET_PITCH,      // Change pitch (value1 = new pitch)
        SET_POSITION,   // Update position (value1=x, value2=y, value3=z)
        PAUSE,          // Pause playback
        RESUME          // Resume playback
    }
    
    public static final Id<AudioControlPayload> ID = new Id<>(Identifier.of("sapphicsaudiolib", "audio_control"));
    
    public static final PacketCodec<RegistryByteBuf, AudioControlPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING.xmap(UUID::fromString, UUID::toString), AudioControlPayload::sessionId,
            PacketCodecs.INTEGER.xmap(i -> ControlType.values()[i], Enum::ordinal), AudioControlPayload::type,
            PacketCodecs.FLOAT, AudioControlPayload::value1,
            PacketCodecs.FLOAT, AudioControlPayload::value2,
            PacketCodecs.FLOAT, AudioControlPayload::value3,
            AudioControlPayload::new
    );
    
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
    
    /**
     * Creates a stop/kill command for a sound session
     */
    public static AudioControlPayload stop(UUID sessionId) {
        return new AudioControlPayload(sessionId, ControlType.STOP, 0, 0, 0);
    }
    
    /**
     * Creates a volume adjustment command
     */
    public static AudioControlPayload setVolume(UUID sessionId, float volume) {
        return new AudioControlPayload(sessionId, ControlType.SET_VOLUME, volume, 0, 0);
    }
    
    /**
     * Creates a pitch adjustment command
     */
    public static AudioControlPayload setPitch(UUID sessionId, float pitch) {
        return new AudioControlPayload(sessionId, ControlType.SET_PITCH, pitch, 0, 0);
    }
    
    /**
     * Creates a position update command
     */
    public static AudioControlPayload setPosition(UUID sessionId, float x, float y, float z) {
        return new AudioControlPayload(sessionId, ControlType.SET_POSITION, x, y, z);
    }
    
    /**
     * Creates a pause command
     */
    public static AudioControlPayload pause(UUID sessionId) {
        return new AudioControlPayload(sessionId, ControlType.PAUSE, 0, 0, 0);
    }
    
    /**
     * Creates a resume command
     */
    public static AudioControlPayload resume(UUID sessionId) {
        return new AudioControlPayload(sessionId, ControlType.RESUME, 0, 0, 0);
    }
}
