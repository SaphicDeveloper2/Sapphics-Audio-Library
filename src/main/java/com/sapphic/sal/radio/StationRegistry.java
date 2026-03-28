package com.sapphic.sal.radio;

import com.sapphic.sal.Sapphicsaudiolib;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registry for known/trusted radio stations.
 * Allows mods to register stations for quick access without API lookups.
 * 
 * <p>Default stations are pre-registered and ready to use.</p>
 */
public class StationRegistry {
    
    private static final Map<String, RadioStation> stations = new ConcurrentHashMap<>();
    private static final Map<String, RadioStation> stationsByName = new ConcurrentHashMap<>();
    private static final List<Consumer<RadioStation>> registrationCallbacks = new ArrayList<>();
    
    // Default stations - registered at class load time
    static {
        registerDefaultStations();
    }
    
    /**
     * Registers the default built-in stations.
     */
    private static void registerDefaultStations() {
        // UpBeat.pw - Default music streaming station
        register(RadioStation.builder()
                .stationUuid("upbeat-pw-default")
                .name("UpBeat")
                .url("https://stream.upbeat.pw/stream")
                .urlResolved("https://stream.upbeat.pw/stream")
                .homepage("https://upbeat.pw")
                .favicon("https://upbeat.pw/favicon.ico")
                .tags("music,electronic,variety")
                .country("Internet")
                .countryCode("XX")
                .codec("MP3")
                .bitrate(192)
                .lastCheckOk(true)
                .build());
        
        Sapphicsaudiolib.LOGGER.info("Registered {} default radio station(s)", stations.size());
    }
    
    /**
     * Registers a radio station in the registry.
     * 
     * @param station The station to register
     * @return true if newly registered, false if already existed
     */
    public static boolean register(RadioStation station) {
        if (station == null || station.stationUuid() == null) {
            return false;
        }
        
        RadioStation existing = stations.putIfAbsent(station.stationUuid(), station);
        if (existing == null) {
            // Newly registered
            stationsByName.put(station.name().toLowerCase(), station);
            
            // Notify callbacks
            for (Consumer<RadioStation> callback : registrationCallbacks) {
                try {
                    callback.accept(station);
                } catch (Exception e) {
                    Sapphicsaudiolib.LOGGER.warn("Station registration callback failed", e);
                }
            }
            
            Sapphicsaudiolib.LOGGER.debug("Registered station: {} ({})", station.name(), station.stationUuid());
            return true;
        }
        return false;
    }
    
    /**
     * Registers multiple stations at once.
     * 
     * @param stationsToRegister Array of stations to register
     * @return Number of newly registered stations
     */
    public static int registerAll(RadioStation... stationsToRegister) {
        int count = 0;
        for (RadioStation station : stationsToRegister) {
            if (register(station)) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Gets a station by its UUID.
     * 
     * @param stationUuid The station's unique identifier
     * @return The station, or null if not found
     */
    public static RadioStation get(String stationUuid) {
        return stations.get(stationUuid);
    }
    
    /**
     * Gets a station by its name (case-insensitive).
     * 
     * @param name The station name
     * @return The station, or null if not found
     */
    public static RadioStation getByName(String name) {
        return stationsByName.get(name.toLowerCase());
    }
    
    /**
     * Gets the default UpBeat station.
     * 
     * @return The UpBeat station
     */
    public static RadioStation getUpBeat() {
        return get("upbeat-pw-default");
    }
    
    /**
     * Gets all registered stations.
     * 
     * @return Unmodifiable collection of all stations
     */
    public static Collection<RadioStation> getAll() {
        return Collections.unmodifiableCollection(stations.values());
    }
    
    /**
     * Gets all station names.
     * 
     * @return Unmodifiable set of station names
     */
    public static Set<String> getNames() {
        return Collections.unmodifiableSet(stationsByName.keySet());
    }
    
    /**
     * Gets the number of registered stations.
     * 
     * @return Station count
     */
    public static int getCount() {
        return stations.size();
    }
    
    /**
     * Checks if a station is registered.
     * 
     * @param stationUuid The station UUID to check
     * @return true if registered
     */
    public static boolean isRegistered(String stationUuid) {
        return stations.containsKey(stationUuid);
    }
    
    /**
     * Unregisters a station. Cannot unregister default stations.
     * 
     * @param stationUuid The station to unregister
     * @return true if removed
     */
    public static boolean unregister(String stationUuid) {
        if (stationUuid.startsWith("upbeat-") || stationUuid.endsWith("-default")) {
            Sapphicsaudiolib.LOGGER.warn("Cannot unregister default station: {}", stationUuid);
            return false;
        }
        
        RadioStation removed = stations.remove(stationUuid);
        if (removed != null) {
            stationsByName.remove(removed.name().toLowerCase());
            return true;
        }
        return false;
    }
    
    /**
     * Adds a callback for station registrations.
     * 
     * @param callback Called when a new station is registered
     */
    public static void onStationRegistered(Consumer<RadioStation> callback) {
        registrationCallbacks.add(callback);
    }
    
    /**
     * Searches registered stations by partial name match.
     * 
     * @param query Search query
     * @return List of matching stations
     */
    public static List<RadioStation> search(String query) {
        String lowerQuery = query.toLowerCase();
        List<RadioStation> results = new ArrayList<>();
        
        for (RadioStation station : stations.values()) {
            if (station.name().toLowerCase().contains(lowerQuery) ||
                station.getTagsString().toLowerCase().contains(lowerQuery)) {
                results.add(station);
            }
        }
        
        return results;
    }
    
    /**
     * Filters stations by codec.
     * 
     * @param codec The codec to filter by (e.g., "MP3", "OGG", "AAC")
     * @return List of stations matching the codec
     */
    public static List<RadioStation> filterByCodec(String codec) {
        List<RadioStation> results = new ArrayList<>();
        
        for (RadioStation station : stations.values()) {
            if (station.codec() != null && station.codec().equalsIgnoreCase(codec)) {
                results.add(station);
            }
        }
        
        return results;
    }
}
