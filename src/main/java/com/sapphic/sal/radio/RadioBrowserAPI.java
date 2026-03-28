package com.sapphic.sal.radio;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sapphic.sal.Sapphicsaudiolib;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client for the Radio Browser API (https://api.radio-browser.info/).
 * Provides methods to search and retrieve internet radio station information.
 * 
 * <p>Usage example:</p>
 * <pre>{@code
 * // Search for jazz stations
 * RadioBrowserAPI.searchByTag("jazz", 10).thenAccept(stations -> {
 *     stations.forEach(s -> System.out.println(s.name() + " - " + s.getStreamUrl()));
 * });
 * 
 * // Get top stations
 * List<RadioStation> popular = RadioBrowserAPI.getTopStations(10).join();
 * }</pre>
 */
public class RadioBrowserAPI {
    
    private static final String USER_AGENT = "SapphicsAudioLib/1.0 (Minecraft Fabric Mod)";
    private static final String DEFAULT_SERVER = "de1.api.radio-browser.info";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    
    private static final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SapphicsAudioLib-RadioAPI");
        t.setDaemon(true);
        return t;
    });
    
    private static final Gson gson = new Gson();
    
    private static String currentServer = DEFAULT_SERVER;
    
    // ==================== SEARCH METHODS ====================
    
    /**
     * Searches for radio stations by name.
     * 
     * @param name Search term (partial match)
     * @param limit Maximum number of results
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> searchByName(String name, int limit) {
        return searchStations("byname/" + encode(name), limit);
    }
    
    /**
     * Searches for radio stations by country name.
     * 
     * @param country Country name (e.g., "United States", "Germany")
     * @param limit Maximum number of results
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> searchByCountry(String country, int limit) {
        return searchStations("bycountry/" + encode(country), limit);
    }
    
    /**
     * Searches for radio stations by ISO country code.
     * 
     * @param countryCode ISO 3166-1 alpha-2 code (e.g., "US", "DE", "GB")
     * @param limit Maximum number of results
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> searchByCountryCode(String countryCode, int limit) {
        return searchStations("bycountrycodeexact/" + encode(countryCode.toUpperCase()), limit);
    }
    
    /**
     * Searches for radio stations by tag/genre.
     * 
     * @param tag Genre tag (e.g., "rock", "jazz", "classical")
     * @param limit Maximum number of results
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> searchByTag(String tag, int limit) {
        return searchStations("bytagexact/" + encode(tag.toLowerCase()), limit);
    }
    
    /**
     * Searches for radio stations by language.
     * 
     * @param language Language name (e.g., "english", "german", "spanish")
     * @param limit Maximum number of results
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> searchByLanguage(String language, int limit) {
        return searchStations("bylanguage/" + encode(language.toLowerCase()), limit);
    }
    
    /**
     * Searches for radio stations by audio codec.
     * 
     * @param codec Codec name (e.g., "MP3", "OGG", "AAC")
     * @param limit Maximum number of results
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> searchByCodec(String codec, int limit) {
        return searchStations("bycodec/" + encode(codec.toUpperCase()), limit);
    }
    
    /**
     * Advanced search with multiple filters.
     * 
     * @param query Search query builder
     * @return CompletableFuture containing list of matching stations
     */
    public static CompletableFuture<List<RadioStation>> advancedSearch(SearchQuery query) {
        String endpoint = "search?" + query.toQueryString();
        return searchStations(endpoint, 0); // Limit is in query string
    }
    
    // ==================== TOP STATIONS ====================
    
    /**
     * Gets the most clicked (popular) stations.
     * 
     * @param count Number of stations to retrieve
     * @return CompletableFuture containing list of popular stations
     */
    public static CompletableFuture<List<RadioStation>> getTopStations(int count) {
        return searchStations("topclick/" + count, 0);
    }
    
    /**
     * Gets the highest voted stations.
     * 
     * @param count Number of stations to retrieve
     * @return CompletableFuture containing list of top-voted stations
     */
    public static CompletableFuture<List<RadioStation>> getTopVotedStations(int count) {
        return searchStations("topvote/" + count, 0);
    }
    
    /**
     * Gets stations with the most recent changes.
     * 
     * @param count Number of stations to retrieve
     * @return CompletableFuture containing list of recently changed stations
     */
    public static CompletableFuture<List<RadioStation>> getRecentStations(int count) {
        return searchStations("lastchange/" + count, 0);
    }
    
    // ==================== STATION BY UUID ====================
    
    /**
     * Gets a specific station by its UUID.
     * 
     * @param stationUuid The station's unique identifier
     * @return CompletableFuture containing the station, or null if not found
     */
    public static CompletableFuture<RadioStation> getStationByUuid(String stationUuid) {
        return searchStations("byuuid/" + encode(stationUuid), 1)
                .thenApply(list -> list.isEmpty() ? null : list.get(0));
    }
    
    /**
     * Registers a click on a station and gets the resolved stream URL.
     * Call this when a user starts playing a station to track popularity.
     * 
     * @param stationUuid The station's unique identifier
     * @return CompletableFuture containing the resolved stream URL
     */
    public static CompletableFuture<String> registerClick(String stationUuid) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://" + currentServer + "/json/url/" + encode(stationUuid);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    if (json.has("url")) {
                        return json.get("url").getAsString();
                    }
                }
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.warn("Failed to register click for station {}: {}", 
                        stationUuid, e.getMessage());
            }
            return null;
        }, executor);
    }
    
    // ==================== METADATA ====================
    
    /**
     * Gets a list of available genres/tags.
     * 
     * @param limit Maximum number of tags to return
     * @return CompletableFuture containing list of tag names
     */
    public static CompletableFuture<List<String>> getTags(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://" + currentServer + "/json/tags?limit=" + limit + "&order=stationcount&reverse=true";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                    List<String> tags = new ArrayList<>();
                    for (JsonElement element : array) {
                        JsonObject obj = element.getAsJsonObject();
                        if (obj.has("name")) {
                            tags.add(obj.get("name").getAsString());
                        }
                    }
                    return tags;
                }
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.warn("Failed to fetch tags: {}", e.getMessage());
            }
            return Collections.emptyList();
        }, executor);
    }
    
    /**
     * Gets a list of countries with station counts.
     * 
     * @return CompletableFuture containing list of country names
     */
    public static CompletableFuture<List<String>> getCountries() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://" + currentServer + "/json/countries?order=stationcount&reverse=true";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    JsonArray array = JsonParser.parseString(response.body()).getAsJsonArray();
                    List<String> countries = new ArrayList<>();
                    for (JsonElement element : array) {
                        JsonObject obj = element.getAsJsonObject();
                        if (obj.has("name")) {
                            countries.add(obj.get("name").getAsString());
                        }
                    }
                    return countries;
                }
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.warn("Failed to fetch countries: {}", e.getMessage());
            }
            return Collections.emptyList();
        }, executor);
    }
    
    // ==================== INTERNAL METHODS ====================
    
    private static CompletableFuture<List<RadioStation>> searchStations(String endpoint, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = "https://" + currentServer + "/json/stations/" + endpoint;
                if (limit > 0 && !endpoint.contains("?")) {
                    url += "?limit=" + limit + "&hidebroken=true";
                } else if (limit > 0) {
                    url += "&limit=" + limit + "&hidebroken=true";
                } else if (!endpoint.contains("hidebroken")) {
                    url += (endpoint.contains("?") ? "&" : "?") + "hidebroken=true";
                }
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                if (response.statusCode() == 200) {
                    return parseStations(response.body());
                } else {
                    Sapphicsaudiolib.LOGGER.warn("Radio Browser API returned status {}", response.statusCode());
                }
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.warn("Failed to search stations: {}", e.getMessage());
            }
            return Collections.emptyList();
        }, executor);
    }
    
    private static List<RadioStation> parseStations(String json) {
        List<RadioStation> stations = new ArrayList<>();
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                RadioStation station = RadioStation.builder()
                        .stationUuid(getString(obj, "stationuuid"))
                        .name(getString(obj, "name"))
                        .url(getString(obj, "url"))
                        .urlResolved(getString(obj, "url_resolved"))
                        .homepage(getString(obj, "homepage"))
                        .favicon(getString(obj, "favicon"))
                        .tags(getString(obj, "tags"))
                        .country(getString(obj, "country"))
                        .countryCode(getString(obj, "countrycode"))
                        .state(getString(obj, "state"))
                        .languages(getString(obj, "language"))
                        .votes(getInt(obj, "votes"))
                        .codec(getString(obj, "codec"))
                        .bitrate(getInt(obj, "bitrate"))
                        .hls(getInt(obj, "hls") == 1)
                        .lastCheckOk(getInt(obj, "lastcheckok") == 1)
                        .clickCount(getInt(obj, "clickcount"))
                        .clickTrend(getInt(obj, "clicktrend"))
                        .geoLat(getDouble(obj, "geo_lat"))
                        .geoLong(getDouble(obj, "geo_long"))
                        .build();
                stations.add(station);
            }
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.warn("Failed to parse station data: {}", e.getMessage());
        }
        return stations;
    }
    
    private static String getString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }
    
    private static int getInt(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
    }
    
    private static Double getDouble(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : null;
    }
    
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    
    /**
     * Sets the API server to use.
     * 
     * @param server Server hostname (e.g., "de1.api.radio-browser.info")
     */
    public static void setServer(String server) {
        currentServer = server;
    }
    
    /**
     * Gets the current API server.
     * 
     * @return Current server hostname
     */
    public static String getServer() {
        return currentServer;
    }
    
    // ==================== SEARCH QUERY BUILDER ====================
    
    /**
     * Builder for advanced search queries.
     */
    public static class SearchQuery {
        private String name;
        private String country;
        private String countryCode;
        private String state;
        private String language;
        private String tag;
        private String codec;
        private Integer bitrateMin;
        private Integer bitrateMax;
        private String order = "clickcount";
        private boolean reverse = true;
        private int limit = 20;
        private int offset = 0;
        
        public SearchQuery name(String name) {
            this.name = name;
            return this;
        }
        
        public SearchQuery country(String country) {
            this.country = country;
            return this;
        }
        
        public SearchQuery countryCode(String countryCode) {
            this.countryCode = countryCode;
            return this;
        }
        
        public SearchQuery state(String state) {
            this.state = state;
            return this;
        }
        
        public SearchQuery language(String language) {
            this.language = language;
            return this;
        }
        
        public SearchQuery tag(String tag) {
            this.tag = tag;
            return this;
        }
        
        public SearchQuery codec(String codec) {
            this.codec = codec;
            return this;
        }
        
        public SearchQuery bitrateMin(int bitrateMin) {
            this.bitrateMin = bitrateMin;
            return this;
        }
        
        public SearchQuery bitrateMax(int bitrateMax) {
            this.bitrateMax = bitrateMax;
            return this;
        }
        
        /**
         * Sets the sort order.
         * 
         * @param order One of: name, votes, clickcount, bitrate, random
         */
        public SearchQuery orderBy(String order) {
            this.order = order;
            return this;
        }
        
        public SearchQuery ascending() {
            this.reverse = false;
            return this;
        }
        
        public SearchQuery descending() {
            this.reverse = true;
            return this;
        }
        
        public SearchQuery limit(int limit) {
            this.limit = limit;
            return this;
        }
        
        public SearchQuery offset(int offset) {
            this.offset = offset;
            return this;
        }
        
        String toQueryString() {
            StringBuilder sb = new StringBuilder();
            if (name != null) sb.append("name=").append(encode(name)).append("&");
            if (country != null) sb.append("country=").append(encode(country)).append("&");
            if (countryCode != null) sb.append("countrycode=").append(encode(countryCode)).append("&");
            if (state != null) sb.append("state=").append(encode(state)).append("&");
            if (language != null) sb.append("language=").append(encode(language)).append("&");
            if (tag != null) sb.append("tag=").append(encode(tag)).append("&");
            if (codec != null) sb.append("codec=").append(encode(codec)).append("&");
            if (bitrateMin != null) sb.append("bitrateMin=").append(bitrateMin).append("&");
            if (bitrateMax != null) sb.append("bitrateMax=").append(bitrateMax).append("&");
            sb.append("order=").append(order).append("&");
            sb.append("reverse=").append(reverse).append("&");
            sb.append("limit=").append(limit).append("&");
            sb.append("offset=").append(offset).append("&");
            sb.append("hidebroken=true");
            return sb.toString();
        }
    }
    
    /**
     * Creates a new search query builder.
     * 
     * @return SearchQuery builder
     */
    public static SearchQuery query() {
        return new SearchQuery();
    }
}
