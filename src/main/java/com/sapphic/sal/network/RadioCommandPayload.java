package com.sapphic.sal.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Network payload for radio debug commands (server -> client).
 * Sent by the /radio command to tell the client to play/stop/control radio streams.
 */
public record RadioCommandPayload(
        CommandType type,
        String stationName,  // Station name or empty for non-play commands
        float value          // Volume value for SET_VOLUME, unused otherwise
) implements CustomPayload {
    
    public enum CommandType {
        PLAY,        // Play a station by name
        STOP,        // Stop current radio
        LIST,        // Request station list (client responds in chat)
        SET_VOLUME,  // Change volume
        STATUS       // Request status (client responds in chat)
    }
    
    public static final Id<RadioCommandPayload> ID = new Id<>(Identifier.of("sapphicsaudiolib", "radio_command"));
    
    public static final PacketCodec<RegistryByteBuf, RadioCommandPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER.xmap(i -> CommandType.values()[i], Enum::ordinal), RadioCommandPayload::type,
            PacketCodecs.STRING, RadioCommandPayload::stationName,
            PacketCodecs.FLOAT, RadioCommandPayload::value,
            RadioCommandPayload::new
    );
    
    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
    
    /**
     * Creates a play command to start streaming a station
     */
    public static RadioCommandPayload play(String stationName) {
        return new RadioCommandPayload(CommandType.PLAY, stationName, 0);
    }
    
    /**
     * Creates a stop command to stop the current radio stream
     */
    public static RadioCommandPayload stop() {
        return new RadioCommandPayload(CommandType.STOP, "", 0);
    }
    
    /**
     * Creates a list command to request available stations
     */
    public static RadioCommandPayload list() {
        return new RadioCommandPayload(CommandType.LIST, "", 0);
    }
    
    /**
     * Creates a volume command to adjust radio volume
     */
    public static RadioCommandPayload setVolume(float volume) {
        return new RadioCommandPayload(CommandType.SET_VOLUME, "", volume);
    }
    
    /**
     * Creates a status command to request current playback status
     */
    public static RadioCommandPayload status() {
        return new RadioCommandPayload(CommandType.STATUS, "", 0);
    }
}
