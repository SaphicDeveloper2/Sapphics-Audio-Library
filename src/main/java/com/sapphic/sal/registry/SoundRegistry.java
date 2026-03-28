package com.sapphic.sal.registry;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sapphic.sal.Sapphicsaudiolib;

/**
 * Registry for valid sound events that can be streamed through the audio library.
 * Acts as a security checkpoint to validate incoming network requests.
 */
public class SoundRegistry {
    
    /**
     * Special sound event ID for user-provided audio files.
     * This ID is always registered and allows players to broadcast any .ogg file to nearby players.
     * Use this when you want to let players share their own audio with others.
     */
    public static final String USER_AUDIO = "sapphicsaudiolib:user_audio";
    
    // Thread-safe set of registered sound event identifiers
    private static final Set<String> registeredSounds = ConcurrentHashMap.newKeySet();
    
    // Static initializer to auto-register the user audio ID
    static {
        registeredSounds.add(USER_AUDIO);
    }
    
    // Callbacks for when sounds are registered (useful for dependent mods)
    private static final Set<SoundRegistrationCallback> callbacks = ConcurrentHashMap.newKeySet();
    
    /**
     * Registers a sound event identifier that can be streamed.
     * Should be called during mod initialization.
     * 
     * @param soundEventId Unique identifier for the sound (e.g., "mymod:tardis_demat")
     * @return true if newly registered, false if already existed
     */
    public static boolean register(String soundEventId) {
        if (soundEventId == null || soundEventId.isEmpty()) {
            throw new IllegalArgumentException("Sound event ID cannot be null or empty");
        }
        
        boolean added = registeredSounds.add(soundEventId);
        if (added) {
            Sapphicsaudiolib.LOGGER.info("Registered streamable sound: {}", soundEventId);
            // Notify callbacks
            for (SoundRegistrationCallback callback : callbacks) {
                try {
                    callback.onSoundRegistered(soundEventId);
                } catch (Exception e) {
                    Sapphicsaudiolib.LOGGER.error("Error in sound registration callback", e);
                }
            }
        }
        return added;
    }
    
    /**
     * Registers multiple sound events at once.
     * 
     * @param soundEventIds Array of sound event identifiers
     */
    public static void registerAll(String... soundEventIds) {
        for (String id : soundEventIds) {
            register(id);
        }
    }
    
    /**
     * Checks if a sound event is registered and valid for streaming.
     * 
     * @param soundEventId The identifier to check
     * @return true if registered and valid
     */
    public static boolean isRegistered(String soundEventId) {
        return soundEventId != null && registeredSounds.contains(soundEventId);
    }
    
    /**
     * Unregisters a sound event.
     * 
     * @param soundEventId The identifier to remove
     * @return true if it was registered and is now removed
     */
    public static boolean unregister(String soundEventId) {
        boolean removed = registeredSounds.remove(soundEventId);
        if (removed) {
            Sapphicsaudiolib.LOGGER.info("Unregistered streamable sound: {}", soundEventId);
        }
        return removed;
    }
    
    /**
     * Gets an unmodifiable view of all registered sound events.
     * 
     * @return Set of registered sound event identifiers
     */
    public static Set<String> getRegisteredSounds() {
        return Collections.unmodifiableSet(new HashSet<>(registeredSounds));
    }
    
    /**
     * Gets the count of registered sounds.
     * 
     * @return Number of registered sound events
     */
    public static int getRegisteredCount() {
        return registeredSounds.size();
    }
    
    /**
     * Clears all registered sounds. Use with caution!
     */
    public static void clearAll() {
        registeredSounds.clear();
        Sapphicsaudiolib.LOGGER.warn("All registered sounds have been cleared");
    }
    
    /**
     * Adds a callback to be notified when new sounds are registered.
     * 
     * @param callback The callback to add
     */
    public static void addRegistrationCallback(SoundRegistrationCallback callback) {
        callbacks.add(callback);
    }
    
    /**
     * Removes a registration callback.
     * 
     * @param callback The callback to remove
     */
    public static void removeRegistrationCallback(SoundRegistrationCallback callback) {
        callbacks.remove(callback);
    }
    
    /**
     * Callback interface for sound registration events.
     */
    @FunctionalInterface
    public interface SoundRegistrationCallback {
        void onSoundRegistered(String soundEventId);
    }
}
