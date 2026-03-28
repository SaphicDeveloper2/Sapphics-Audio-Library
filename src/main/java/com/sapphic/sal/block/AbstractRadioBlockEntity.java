package com.sapphic.sal.block;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Abstract base class for radio block entities.
 * Handles station storage, power state, and synchronization.
 * 
 * <p>The actual audio streaming is handled on the client side via
 * {@link com.sapphic.sal.client.radio.RadioStreamController}.</p>
 * 
 * <h2>Example Implementation:</h2>
 * <pre>{@code
 * public class MyRadioBlockEntity extends AbstractRadioBlockEntity {
 *     public MyRadioBlockEntity(BlockPos pos, BlockState state) {
 *         super(MyBlockEntities.RADIO, pos, state);
 *     }
 * }
 * }</pre>
 * 
 * <h2>Client-Side Mixin/Handler Example:</h2>
 * <pre>{@code
 * // In your client initializer or a WorldRenderEvents handler:
 * ClientTickEvents.END_WORLD_TICK.register(world -> {
 *     for (BlockEntity be : world.blockEntities) {
 *         if (be instanceof AbstractRadioBlockEntity radio) {
 *             radio.clientTick(world, be.getPos(), be.getCachedState(), radio);
 *         }
 *     }
 * });
 * }</pre>
 */
public abstract class AbstractRadioBlockEntity extends BlockEntity {
    
    /** NBT key for the station UUID */
    protected static final String NBT_STATION_UUID = "StationUuid";
    /** NBT key for the station name (for display/fallback) */
    protected static final String NBT_STATION_NAME = "StationName";
    /** NBT key for volume */
    protected static final String NBT_VOLUME = "Volume";
    /** NBT key for custom stream URL */
    protected static final String NBT_CUSTOM_URL = "CustomUrl";
    
    /** The current radio station UUID */
    protected String stationUuid;
    /** The current radio station name */
    protected String stationName;
    /** The current volume (0.0 to 1.0) */
    protected float volume = 0.8f;
    /** Custom stream URL (overrides station if set) */
    protected String customStreamUrl;
    
    // Client-side streaming state
    /** Tracks if we're currently streaming on client */
    protected transient boolean isStreaming = false;
    /** The active stream session ID */
    protected transient UUID streamSessionId;
    
    /**
     * Creates a new radio block entity.
     * 
     * @param type Block entity type
     * @param pos Block position
     * @param state Block state
     */
    public AbstractRadioBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        
        // Default to UpBeat station
        RadioStation defaultStation = StationRegistry.getUpBeat();
        if (defaultStation != null) {
            this.stationUuid = defaultStation.stationUuid();
            this.stationName = defaultStation.name();
        }
    }
    
    /**
     * Called when the radio is turned on (server side).
     * Override to add custom behavior.
     */
    public void onTurnedOn() {
        Sapphicsaudiolib.LOGGER.debug("Radio turned on at {}", pos);
        markDirty();
        syncToClients();
    }
    
    /**
     * Called when the radio is turned off (server side).
     * Override to add custom behavior.
     */
    public void onTurnedOff() {
        Sapphicsaudiolib.LOGGER.debug("Radio turned off at {}", pos);
        markDirty();
        syncToClients();
    }
    
    /**
     * Checks if the radio is currently powered/playing.
     * 
     * @return True if the radio should be playing
     */
    public boolean isPowered() {
        return getCachedState().get(AbstractRadioBlock.POWERED);
    }
    
    /**
     * Gets the current radio station.
     * First checks the registry, then falls back to a custom station.
     * 
     * @return The current station, or null if not found
     */
    @Nullable
    public RadioStation getStation() {
        if (stationUuid != null) {
            RadioStation station = StationRegistry.get(stationUuid);
            if (station != null) {
                return station;
            }
        }
        
        // Fallback: create a minimal station from custom URL
        if (customStreamUrl != null && !customStreamUrl.isEmpty()) {
            return RadioStation.builder()
                    .stationUuid("custom-" + pos.toShortString())
                    .name(stationName != null ? stationName : "Custom Station")
                    .url(customStreamUrl)
                    .urlResolved(customStreamUrl)
                    .codec("MP3") // Assume MP3 for custom streams
                    .bitrate(192)
                    .lastCheckOk(true)
                    .build();
        }
        
        return null;
    }
    
    /**
     * Sets the radio station by UUID.
     * The station must be registered in {@link StationRegistry}.
     * 
     * @param stationUuid Station UUID from the registry
     */
    public void setStation(String stationUuid) {
        RadioStation station = StationRegistry.get(stationUuid);
        if (station != null) {
            this.stationUuid = stationUuid;
            this.stationName = station.name();
            this.customStreamUrl = null;
            markDirty();
            syncToClients();
        }
    }
    
    /**
     * Sets a custom stream URL (not from registry).
     * 
     * @param name Display name for the station
     * @param url Stream URL
     */
    public void setCustomStation(String name, String url) {
        this.stationUuid = null;
        this.stationName = name;
        this.customStreamUrl = url;
        markDirty();
        syncToClients();
    }
    
    /**
     * Gets the current volume.
     * 
     * @return Volume (0.0 to 1.0)
     */
    public float getVolume() {
        return volume;
    }
    
    /**
     * Sets the volume.
     * 
     * @param volume Volume (0.0 to 1.0)
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        markDirty();
        syncToClients();
    }
    
    /**
     * Gets the station name for display.
     * 
     * @return Station name
     */
    public String getStationName() {
        return stationName != null ? stationName : "Unknown Station";
    }
    
    // ==================== Client-Side Streaming ====================
    
    /**
     * Client-side tick handler. Call this from your client tick event.
     * Manages starting/stopping the audio stream based on power state.
     */
    public static <T extends AbstractRadioBlockEntity> void clientTick(World world, BlockPos pos, BlockState state, T blockEntity) {
        if (!world.isClient) return;
        
        boolean shouldPlay = state.get(AbstractRadioBlock.POWERED);
        
        if (shouldPlay && !blockEntity.isStreaming) {
            blockEntity.startClientStream();
        } else if (!shouldPlay && blockEntity.isStreaming) {
            blockEntity.stopClientStream();
        }
    }
    
    /**
     * Starts the client-side audio stream.
     * Override to customize streaming behavior.
     */
    protected void startClientStream() {
        // This will be called on the client
        // The actual implementation uses RadioStreamController
        // We set the flag here - the client mixin/handler should check this
        isStreaming = true;
        Sapphicsaudiolib.LOGGER.debug("Client: Starting stream for radio at {}", pos);
    }
    
    /**
     * Stops the client-side audio stream.
     * Override to customize streaming behavior.
     */
    protected void stopClientStream() {
        isStreaming = false;
        streamSessionId = null;
        Sapphicsaudiolib.LOGGER.debug("Client: Stopping stream for radio at {}", pos);
    }
    
    /**
     * Whether the client is currently streaming audio.
     * 
     * @return True if streaming
     */
    public boolean isStreaming() {
        return isStreaming;
    }
    
    /**
     * Sets the stream session ID (called by client-side code).
     * 
     * @param sessionId The active session ID
     */
    public void setStreamSessionId(UUID sessionId) {
        this.streamSessionId = sessionId;
    }
    
    /**
     * Gets the current stream session ID.
     * 
     * @return Session ID or null if not streaming
     */
    @Nullable
    public UUID getStreamSessionId() {
        return streamSessionId;
    }
    
    // ==================== NBT Serialization ====================
    
    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        
        if (stationUuid != null) {
            nbt.putString(NBT_STATION_UUID, stationUuid);
        }
        if (stationName != null) {
            nbt.putString(NBT_STATION_NAME, stationName);
        }
        nbt.putFloat(NBT_VOLUME, volume);
        if (customStreamUrl != null) {
            nbt.putString(NBT_CUSTOM_URL, customStreamUrl);
        }
    }
    
    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        
        if (nbt.contains(NBT_STATION_UUID)) {
            stationUuid = nbt.getString(NBT_STATION_UUID);
        }
        if (nbt.contains(NBT_STATION_NAME)) {
            stationName = nbt.getString(NBT_STATION_NAME);
        }
        if (nbt.contains(NBT_VOLUME)) {
            volume = nbt.getFloat(NBT_VOLUME);
        }
        if (nbt.contains(NBT_CUSTOM_URL)) {
            customStreamUrl = nbt.getString(NBT_CUSTOM_URL);
        }
    }
    
    // ==================== Network Sync ====================
    
    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
        NbtCompound nbt = new NbtCompound();
        writeNbt(nbt, registryLookup);
        return nbt;
    }
    
    @Override
    @Nullable
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
    
    /**
     * Syncs this block entity to all clients tracking it.
     */
    protected void syncToClients() {
        if (world != null && !world.isClient) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
        }
    }
}
