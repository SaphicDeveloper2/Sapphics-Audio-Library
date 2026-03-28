package com.sapphic.sal.api;

import java.util.Set;

import com.sapphic.sal.registry.SoundRegistry;

/**
 * Main public API for SapphicsAudioLib - Common (server + client) registration methods.
 * 
 * For client-side playback methods, use {@code SapphicsAudioClientAPI} from the client package.
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // During mod initialization - register your sound events
 * SapphicsAudioAPI.registerSound("mymod:tardis_demat");
 * SapphicsAudioAPI.registerSound("mymod:sonic_screwdriver");
 * 
 * // For playback (client-side only), use SapphicsAudioClientAPI:
 * // SapphicsAudioClientAPI.playFromEntity(audioPath, entity, "mymod:tardis_demat", options);
 * }</pre>
 */
public final class SapphicsAudioAPI {
    
    private SapphicsAudioAPI() {} // Prevent instantiation
    
    // ==================== REGISTRATION ====================
    
    /**
     * Registers a sound event identifier that can be streamed.
     * Must be called during mod initialization for each sound type your mod uses.
     * 
     * @param soundEventId Unique identifier (e.g., "mymod:tardis_demat")
     * @return true if newly registered, false if already existed
     */
    public static boolean registerSound(String soundEventId) {
        return SoundRegistry.register(soundEventId);
    }
    
    /**
     * Registers multiple sound events at once.
     * 
     * @param soundEventIds Array of sound identifiers
     */
    public static void registerSounds(String... soundEventIds) {
        SoundRegistry.registerAll(soundEventIds);
    }
    
    /**
     * Checks if a sound event is registered.
     * 
     * @param soundEventId The identifier to check
     * @return true if registered
     */
    public static boolean isSoundRegistered(String soundEventId) {
        return SoundRegistry.isRegistered(soundEventId);
    }
    
    /**
     * Adds a callback to be notified when sounds are registered.
     * Useful for compatibility checks between mods.
     * 
     * @param callback The callback to add
     */
    public static void onSoundRegistered(SoundRegistry.SoundRegistrationCallback callback) {
        SoundRegistry.addRegistrationCallback(callback);
    }
    
    /**
     * Gets all registered sound event IDs.
     * 
     * @return Unmodifiable set of registered sounds
     */
    public static Set<String> getRegisteredSounds() {
        return SoundRegistry.getRegisteredSounds();
    }
    
    /**
     * Gets the count of registered sounds.
     */
    public static int getRegisteredSoundCount() {
        return SoundRegistry.getRegisteredCount();
    }
    
    /**
     * Unregisters a sound event. Use with caution.
     * 
     * @param soundEventId The sound to unregister
     * @return true if it was registered
     */
    public static boolean unregisterSound(String soundEventId) {
        return SoundRegistry.unregister(soundEventId);
    }
}
