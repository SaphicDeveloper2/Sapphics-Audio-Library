package com.sapphic.sal.radio;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents a radio station from the Radio Browser API.
 * Immutable record containing all relevant station metadata.
 */
public record RadioStation(
        String stationUuid,
        String name,
        String url,
        String urlResolved,
        String homepage,
        String favicon,
        List<String> tags,
        String country,
        String countryCode,
        String state,
        List<String> languages,
        int votes,
        String codec,
        int bitrate,
        boolean hls,
        boolean lastCheckOk,
        int clickCount,
        int clickTrend,
        Double geoLat,
        Double geoLong
) {
    
    /**
     * Creates a RadioStation with tags and languages as lists.
     */
    public RadioStation {
        // Ensure lists are immutable
        tags = tags != null ? Collections.unmodifiableList(tags) : Collections.emptyList();
        languages = languages != null ? Collections.unmodifiableList(languages) : Collections.emptyList();
    }
    
    /**
     * Gets the best URL for streaming. Prefers the resolved URL.
     * 
     * @return The stream URL to use for playback
     */
    public String getStreamUrl() {
        return urlResolved != null && !urlResolved.isEmpty() ? urlResolved : url;
    }
    
    /**
     * Checks if this station is currently online/working.
     * 
     * @return true if the last check was successful
     */
    public boolean isOnline() {
        return lastCheckOk;
    }
    
    /**
     * Checks if this station streams in Ogg Vorbis format.
     * The default decoder only supports Ogg.
     * 
     * @return true if codec is OGG
     */
    public boolean isOggFormat() {
        return codec != null && codec.equalsIgnoreCase("OGG");
    }
    
    /**
     * Checks if this station streams in MP3 format.
     * 
     * @return true if codec is MP3
     */
    public boolean isMp3Format() {
        return codec != null && codec.equalsIgnoreCase("MP3");
    }
    
    /**
     * Checks if this station streams in AAC format.
     * 
     * @return true if codec is AAC or AAC+
     */
    public boolean isAacFormat() {
        return codec != null && (codec.equalsIgnoreCase("AAC") || codec.equalsIgnoreCase("AAC+"));
    }
    
    /**
     * Gets the tags as a comma-separated string.
     * 
     * @return Tags string
     */
    public String getTagsString() {
        return String.join(", ", tags);
    }
    
    /**
     * Gets the languages as a comma-separated string.
     * 
     * @return Languages string
     */
    public String getLanguagesString() {
        return String.join(", ", languages);
    }
    
    /**
     * Builder for creating RadioStation instances from API responses.
     */
    public static class Builder {
        private String stationUuid;
        private String name;
        private String url;
        private String urlResolved;
        private String homepage;
        private String favicon;
        private List<String> tags = Collections.emptyList();
        private String country;
        private String countryCode;
        private String state;
        private List<String> languages = Collections.emptyList();
        private int votes;
        private String codec;
        private int bitrate;
        private boolean hls;
        private boolean lastCheckOk;
        private int clickCount;
        private int clickTrend;
        private Double geoLat;
        private Double geoLong;
        
        public Builder stationUuid(String stationUuid) {
            this.stationUuid = stationUuid;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder url(String url) {
            this.url = url;
            return this;
        }
        
        public Builder urlResolved(String urlResolved) {
            this.urlResolved = urlResolved;
            return this;
        }
        
        public Builder homepage(String homepage) {
            this.homepage = homepage;
            return this;
        }
        
        public Builder favicon(String favicon) {
            this.favicon = favicon;
            return this;
        }
        
        public Builder tags(String tagsCsv) {
            if (tagsCsv != null && !tagsCsv.isEmpty()) {
                this.tags = Arrays.asList(tagsCsv.split(","));
            }
            return this;
        }
        
        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }
        
        public Builder country(String country) {
            this.country = country;
            return this;
        }
        
        public Builder countryCode(String countryCode) {
            this.countryCode = countryCode;
            return this;
        }
        
        public Builder state(String state) {
            this.state = state;
            return this;
        }
        
        public Builder languages(String languagesCsv) {
            if (languagesCsv != null && !languagesCsv.isEmpty()) {
                this.languages = Arrays.asList(languagesCsv.split(","));
            }
            return this;
        }
        
        public Builder languages(List<String> languages) {
            this.languages = languages;
            return this;
        }
        
        public Builder votes(int votes) {
            this.votes = votes;
            return this;
        }
        
        public Builder codec(String codec) {
            this.codec = codec;
            return this;
        }
        
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }
        
        public Builder hls(boolean hls) {
            this.hls = hls;
            return this;
        }
        
        public Builder lastCheckOk(boolean lastCheckOk) {
            this.lastCheckOk = lastCheckOk;
            return this;
        }
        
        public Builder clickCount(int clickCount) {
            this.clickCount = clickCount;
            return this;
        }
        
        public Builder clickTrend(int clickTrend) {
            this.clickTrend = clickTrend;
            return this;
        }
        
        public Builder geoLat(Double geoLat) {
            this.geoLat = geoLat;
            return this;
        }
        
        public Builder geoLong(Double geoLong) {
            this.geoLong = geoLong;
            return this;
        }
        
        public RadioStation build() {
            return new RadioStation(
                    stationUuid, name, url, urlResolved, homepage, favicon,
                    tags, country, countryCode, state, languages,
                    votes, codec, bitrate, hls, lastCheckOk,
                    clickCount, clickTrend, geoLat, geoLong
            );
        }
    }
    
    public static Builder builder() {
        return new Builder();
    }
}
