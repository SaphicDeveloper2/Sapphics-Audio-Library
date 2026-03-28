package com.sapphic.sal.radio;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sapphic.sal.Sapphicsaudiolib;

/**
 * Client for the UpBeat Radio API (https://upbeatradio.net/api/v1).
 * Provides methods to get current song info, recently played tracks, and schedule.
 * 
 * <p>API Endpoints:</p>
 * <ul>
 *   <li>GET /stats - Current song, presenter, listener count</li>
 *   <li>GET /recentlyPlayed - Last 50 songs played</li>
 *   <li>GET /booked - Weekly schedule</li>
 * </ul>
 */
public class UpBeatAPI {
    
    private static final String BASE_URL = "https://upbeatradio.net/api/v1";
    private static final String USER_AGENT = "SapphicsAudioLib/1.0 (Minecraft Fabric Mod)";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    
    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SapphicsAudioLib-UpBeatAPI");
        t.setDaemon(true);
        return t;
    });
    
    private static final Gson gson = new Gson();
    
    // ==================== DATA CLASSES ====================
    
    /**
     * Represents the current playing song on UpBeat.
     */
    public record SongInfo(
        String title,
        String artist,
        String artUrl,
        String previewUrl,
        String spotifyId,
        int likes,
        int dislikes,
        int favourites,
        int playCount
    ) {
        public boolean hasSpotify() {
            return spotifyId != null && !spotifyId.equals("-1");
        }
        
        public boolean hasPreview() {
            return previewUrl != null && !previewUrl.equals("-1");
        }
    }
    
    /**
     * Represents the current presenter/DJ on air.
     */
    public record Presenter(
        String name,
        int likes,
        String profileUrl,
        String avatarUrl,
        String id,
        int day,
        int hour,
        boolean isShow
    ) {
        public boolean isAutoDJ() {
            return id == null || id.equals("-1");
        }
    }
    
    /**
     * Represents the complete stats response.
     */
    public record Stats(
        SongInfo currentSong,
        Presenter presenter,
        int listeners,
        String listenUrl,
        String lastUpdated
    ) {}
    
    /**
     * Represents a recently played song entry.
     */
    public record RecentSong(
        String title,
        String artist,
        String previewUrl,
        String spotifyId,
        int likes,
        int dislikes,
        int favourites,
        int playCount,
        long playedAt
    ) {
        public String getTimeAgo() {
            long now = Instant.now().getEpochSecond();
            long diff = now - playedAt;
            
            if (diff < 60) return diff + "s ago";
            if (diff < 3600) return (diff / 60) + "m ago";
            if (diff < 86400) return (diff / 3600) + "h ago";
            return (diff / 86400) + "d ago";
        }
    }
    
    /**
     * Represents a booked slot in the schedule.
     */
    public record BookedSlot(
        String name,
        String avatarUrl,
        int userId,
        int day,
        int hour,
        int week,
        String profileUrl
    ) {
        public String getDayName() {
            return switch (day) {
                case 0 -> "Sunday";
                case 1 -> "Monday";
                case 2 -> "Tuesday";
                case 3 -> "Wednesday";
                case 4 -> "Thursday";
                case 5 -> "Friday";
                case 6 -> "Saturday";
                default -> "Day " + day;
            };
        }
        
        public String getTimeString() {
            return String.format("%02d:00 UTC", hour);
        }
    }
    
    // ==================== API METHODS ====================
    
    /**
     * Gets the current stats including now playing song, presenter, and listener count.
     * 
     * @return CompletableFuture containing the stats
     */
    public static CompletableFuture<Stats> getStats() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/stats"))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    Sapphicsaudiolib.LOGGER.warn("UpBeat API returned status: {}", response.statusCode());
                    return null;
                }
                
                return parseStats(response.body());
                
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.error("Failed to fetch UpBeat stats", e);
                return null;
            }
        }, executor);
    }
    
    /**
     * Gets the last 50 recently played songs.
     * 
     * @return CompletableFuture containing list of recent songs
     */
    public static CompletableFuture<List<RecentSong>> getRecentlyPlayed() {
        return getRecentlyPlayed(50);
    }
    
    /**
     * Gets recently played songs with a limit.
     * 
     * @param limit Maximum number of songs to return (max 50)
     * @return CompletableFuture containing list of recent songs
     */
    public static CompletableFuture<List<RecentSong>> getRecentlyPlayed(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/recentlyPlayed"))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    Sapphicsaudiolib.LOGGER.warn("UpBeat API returned status: {}", response.statusCode());
                    return Collections.emptyList();
                }
                
                return parseRecentlyPlayed(response.body(), limit);
                
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.error("Failed to fetch UpBeat recently played", e);
                return Collections.emptyList();
            }
        }, executor);
    }
    
    /**
     * Gets the current week's booked schedule.
     * 
     * @return CompletableFuture containing list of booked slots
     */
    public static CompletableFuture<List<BookedSlot>> getSchedule() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/booked"))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, 
                        HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() != 200) {
                    Sapphicsaudiolib.LOGGER.warn("UpBeat API returned status: {}", response.statusCode());
                    return Collections.emptyList();
                }
                
                return parseSchedule(response.body());
                
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.error("Failed to fetch UpBeat schedule", e);
                return Collections.emptyList();
            }
        }, executor);
    }
    
    // ==================== PARSING METHODS ====================
    
    private static Stats parseStats(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            
            // Parse song info
            SongInfo song = null;
            if (root.has("song") && !root.get("song").isJsonNull()) {
                JsonObject songObj = root.getAsJsonObject("song");
                song = new SongInfo(
                    getStringOrNull(songObj, "title"),
                    getStringOrNull(songObj, "artist"),
                    getStringOrNull(songObj, "art"),
                    getStringOrNull(songObj, "preview"),
                    getStringOrNull(songObj, "spotify_id"),
                    getIntOrDefault(songObj, "likes", -1),
                    getIntOrDefault(songObj, "dislikes", -1),
                    getIntOrDefault(songObj, "favourites", -1),
                    getIntOrDefault(songObj, "played", 0)
                );
            }
            
            // Parse presenter info
            Presenter presenter = null;
            if (root.has("onair") && !root.get("onair").isJsonNull()) {
                JsonObject onairObj = root.getAsJsonObject("onair");
                presenter = new Presenter(
                    getStringOrNull(onairObj, "name"),
                    getIntOrDefault(onairObj, "likes", -1),
                    getStringOrNull(onairObj, "profile_url"),
                    getStringOrNull(onairObj, "avatar"),
                    getStringOrNull(onairObj, "id"),
                    getIntOrDefault(onairObj, "day", -1),
                    getIntOrDefault(onairObj, "hour", -1),
                    onairObj.has("show") && onairObj.get("show").getAsBoolean()
                );
            }
            
            return new Stats(
                song,
                presenter,
                getIntOrDefault(root, "listeners", 0),
                getStringOrNull(root, "listen_url"),
                getStringOrNull(root, "last_updated")
            );
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Failed to parse UpBeat stats JSON", e);
            return null;
        }
    }
    
    private static List<RecentSong> parseRecentlyPlayed(String json, int limit) {
        List<RecentSong> songs = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            
            int count = 0;
            for (var element : arr) {
                if (count >= limit) break;
                
                JsonObject obj = element.getAsJsonObject();
                songs.add(new RecentSong(
                    getStringOrNull(obj, "title"),
                    getStringOrNull(obj, "artist"),
                    getStringOrNull(obj, "preview"),
                    getStringOrNull(obj, "spotify_id"),
                    getIntOrDefault(obj, "likes", 0),
                    getIntOrDefault(obj, "dislikes", 0),
                    getIntOrDefault(obj, "favourites", 0),
                    getIntOrDefault(obj, "played", 0),
                    getLongOrDefault(obj, "time", 0)
                ));
                count++;
            }
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Failed to parse UpBeat recently played JSON", e);
        }
        return songs;
    }
    
    private static List<BookedSlot> parseSchedule(String json) {
        List<BookedSlot> slots = new ArrayList<>();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            
            for (var element : arr) {
                JsonObject obj = element.getAsJsonObject();
                slots.add(new BookedSlot(
                    getStringOrNull(obj, "name"),
                    getStringOrNull(obj, "avatar"),
                    getIntOrDefault(obj, "user_id", -1),
                    getIntOrDefault(obj, "day", -1),
                    getIntOrDefault(obj, "hour", -1),
                    getIntOrDefault(obj, "week", -1),
                    getStringOrNull(obj, "profile_url")
                ));
            }
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Failed to parse UpBeat schedule JSON", e);
        }
        return slots;
    }
    
    // ==================== UTILITY METHODS ====================
    
    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        String value = obj.get(key).getAsString();
        return value.equals("-1") ? null : value;
    }
    
    private static int getIntOrDefault(JsonObject obj, String key, int defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return defaultValue;
        }
    }
    
    private static long getLongOrDefault(JsonObject obj, String key, long defaultValue) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
