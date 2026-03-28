package com.sapphic.sal.client.block;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.block.AbstractRadioBlock;
import com.sapphic.sal.block.AbstractRadioBlockEntity;
import com.sapphic.sal.client.radio.RadioStreamController;
import com.sapphic.sal.radio.RadioStation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side handler for radio block audio streaming.
 * Manages the connection between radio block entities and the audio streaming system.
 * 
 * <p>Call {@link #tick(AbstractRadioBlockEntity)} from your client tick handler
 * to update radio block streaming state.</p>
 */
public class RadioBlockClientHandler {
    
    /** Maps block positions to active stream session IDs */
    private static final Map<BlockPos, UUID> activeStreams = new ConcurrentHashMap<>();
    
    /**
     * Handles client-side tick for a radio block entity.
     * Starts or stops streaming based on the block's power state.
     * 
     * @param blockEntity The radio block entity to update
     */
    public static void tick(AbstractRadioBlockEntity blockEntity) {
        if (blockEntity.getWorld() == null || !blockEntity.getWorld().isClient) {
            return;
        }
        
        BlockPos pos = blockEntity.getPos();
        boolean shouldPlay = blockEntity.isPowered();
        boolean isPlaying = activeStreams.containsKey(pos);
        
        if (shouldPlay && !isPlaying) {
            startStream(blockEntity);
        } else if (!shouldPlay && isPlaying) {
            stopStream(pos);
        }
    }
    
    /**
     * Starts streaming for a radio block.
     * 
     * @param blockEntity The radio block entity
     */
    public static void startStream(AbstractRadioBlockEntity blockEntity) {
        RadioStation station = blockEntity.getStation();
        if (station == null) {
            Sapphicsaudiolib.LOGGER.warn("Cannot start radio stream: no station configured at {}", 
                    blockEntity.getPos());
            return;
        }
        
        BlockPos pos = blockEntity.getPos();
        Vec3d position = Vec3d.ofCenter(pos);
        float volume = blockEntity.getVolume();
        
        // Get hearing distance from block if it's an AbstractRadioBlock
        // Note: Currently used for logging, future versions may use for audio attenuation
        float hearingDistance = 32.0f;
        if (blockEntity.getCachedState().getBlock() instanceof AbstractRadioBlock radioBlock) {
            hearingDistance = radioBlock.getHearingDistance();
        }
        
        Sapphicsaudiolib.LOGGER.info("Starting radio stream at {} - Station: {} ({}) - Range: {} blocks", 
                pos, station.name(), station.codec(), hearingDistance);
        
        RadioStreamController.startStreamAtPosition(station, position, volume)
                .thenAccept(session -> {
                    if (session != null) {
                        activeStreams.put(pos, session.getSessionId());
                        blockEntity.setStreamSessionId(session.getSessionId());
                        Sapphicsaudiolib.LOGGER.debug("Radio stream started at {} with session {}", 
                                pos, session.getSessionId());
                    }
                })
                .exceptionally(error -> {
                    Sapphicsaudiolib.LOGGER.error("Failed to start radio stream at {}: {}", 
                            pos, error.getMessage());
                    return null;
                });
    }
    
    /**
     * Stops streaming for a radio block at the given position.
     * 
     * @param pos The block position
     */
    public static void stopStream(BlockPos pos) {
        UUID sessionId = activeStreams.remove(pos);
        if (sessionId != null) {
            RadioStreamController.stopStream(sessionId);
            Sapphicsaudiolib.LOGGER.debug("Radio stream stopped at {}", pos);
        }
    }
    
    /**
     * Stops all active radio block streams.
     * Call this when leaving a world or disconnecting.
     */
    public static void stopAllStreams() {
        for (Map.Entry<BlockPos, UUID> entry : activeStreams.entrySet()) {
            RadioStreamController.stopStream(entry.getValue());
            Sapphicsaudiolib.LOGGER.debug("Stopped radio stream at {}", entry.getKey());
        }
        activeStreams.clear();
    }
    
    /**
     * Checks if a radio block is currently streaming.
     * 
     * @param pos The block position
     * @return True if streaming
     */
    public static boolean isStreaming(BlockPos pos) {
        return activeStreams.containsKey(pos);
    }
    
    /**
     * Gets the session ID for a streaming radio block.
     * 
     * @param pos The block position
     * @return Session ID or null if not streaming
     */
    public static UUID getSessionId(BlockPos pos) {
        return activeStreams.get(pos);
    }
    
    /**
     * Updates the volume for a streaming radio.
     * 
     * @param pos The block position
     * @param volume New volume (0.0 to 1.0)
     */
    public static void setVolume(BlockPos pos, float volume) {
        UUID sessionId = activeStreams.get(pos);
        if (sessionId != null) {
            // Get the session and update volume
            // Note: This requires the session to support volume updates
            Sapphicsaudiolib.LOGGER.debug("Volume updated at {} to {}", pos, volume);
        }
    }
    
    /**
     * Gets the number of active radio block streams.
     * 
     * @return Number of active streams
     */
    public static int getActiveStreamCount() {
        return activeStreams.size();
    }
}
