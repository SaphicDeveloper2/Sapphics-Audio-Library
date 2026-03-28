package com.sapphic.sal.client.audio.codec;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.AudioDecoder.DecodedAudio;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for audio codecs.
 * Manages codec detection and provides automatic format detection.
 */
public class CodecRegistry {
    
    private static final Map<String, AudioCodec> codecs = new ConcurrentHashMap<>();
    private static final List<AudioCodec> codecList = new ArrayList<>();
    
    static {
        // Register built-in codecs in priority order
        register(OggVorbisCodec.getInstance()); // Primary codec
        register(Mp3Codec.getInstance());       // MP3 support
        register(AacCodec.getInstance());       // AAC support
        
        Sapphicsaudiolib.LOGGER.info("Registered {} audio codecs: {}", 
                codecs.size(), String.join(", ", codecs.keySet()));
    }
    
    /**
     * Registers an audio codec.
     * 
     * @param codec The codec to register
     */
    public static void register(AudioCodec codec) {
        codecs.put(codec.getName().toUpperCase(), codec);
        codecList.add(codec);
        
        // Also register by extensions
        for (String ext : codec.getExtensions()) {
            codecs.putIfAbsent(ext.toUpperCase(), codec);
        }
    }
    
    /**
     * Gets a codec by name.
     * 
     * @param name Codec name (e.g., "OGG", "MP3", "AAC")
     * @return The codec, or null if not found
     */
    public static AudioCodec get(String name) {
        return codecs.get(name.toUpperCase());
    }
    
    /**
     * Gets a codec by file extension.
     * 
     * @param extension File extension (e.g., "ogg", "mp3")
     * @return The codec, or null if not found
     */
    public static AudioCodec getByExtension(String extension) {
        String ext = extension.startsWith(".") ? extension.substring(1) : extension;
        return codecs.get(ext.toUpperCase());
    }
    
    /**
     * Detects the codec for the given audio data.
     * Tests each codec's canDecode method.
     * 
     * @param data Audio data to analyze
     * @return The detected codec, or null if unknown
     */
    public static AudioCodec detect(byte[] data) {
        for (AudioCodec codec : codecList) {
            if (codec.canDecode(data)) {
                return codec;
            }
        }
        return null;
    }
    
    /**
     * Decodes audio data using automatic codec detection.
     * 
     * @param data Audio data to decode
     * @return Decoded audio
     * @throws AudioCodec.CodecException if decoding fails or format is unknown
     */
    public static DecodedAudio decode(byte[] data) throws AudioCodec.CodecException {
        AudioCodec codec = detect(data);
        
        if (codec == null) {
            throw new AudioCodec.CodecException("Unknown audio format - no codec found");
        }
        
        Sapphicsaudiolib.LOGGER.debug("Auto-detected codec: {}", codec.getName());
        return codec.decode(data);
    }
    
    /**
     * Decodes audio data using a specific codec.
     * 
     * @param data Audio data to decode
     * @param codecName Codec name (e.g., "OGG", "MP3")
     * @return Decoded audio
     * @throws AudioCodec.CodecException if decoding fails
     */
    public static DecodedAudio decode(byte[] data, String codecName) throws AudioCodec.CodecException {
        AudioCodec codec = get(codecName);
        
        if (codec == null) {
            throw new AudioCodec.CodecException("Unknown codec: " + codecName);
        }
        
        return codec.decode(data);
    }
    
    /**
     * Gets all registered codecs.
     * 
     * @return Unmodifiable collection of codecs
     */
    public static Collection<AudioCodec> getAll() {
        return Collections.unmodifiableList(codecList);
    }
    
    /**
     * Gets all registered codec names.
     * 
     * @return Set of codec names
     */
    public static Set<String> getNames() {
        Set<String> names = new LinkedHashSet<>();
        for (AudioCodec codec : codecList) {
            names.add(codec.getName());
        }
        return names;
    }
    
    /**
     * Checks if a codec is available and working.
     * 
     * @param codecName Codec name
     * @return true if the codec is registered and available
     */
    public static boolean isAvailable(String codecName) {
        AudioCodec codec = get(codecName);
        if (codec == null) {
            return false;
        }
        
        // Check specific availability
        if (codec instanceof Mp3Codec) {
            return Mp3Codec.isAvailable();
        }
        if (codec instanceof AacCodec) {
            return AacCodec.isAvailable();
        }
        
        return true; // OGG is always available
    }
    
    /**
     * Gets a status report of all codecs.
     * 
     * @return Map of codec name to availability status
     */
    public static Map<String, Boolean> getStatus() {
        Map<String, Boolean> status = new LinkedHashMap<>();
        for (AudioCodec codec : codecList) {
            status.put(codec.getName(), isAvailable(codec.getName()));
        }
        return status;
    }
    
    /**
     * Gets supported file extensions.
     * 
     * @return List of all supported extensions
     */
    public static List<String> getSupportedExtensions() {
        List<String> extensions = new ArrayList<>();
        for (AudioCodec codec : codecList) {
            if (isAvailable(codec.getName())) {
                extensions.addAll(Arrays.asList(codec.getExtensions()));
            }
        }
        return extensions;
    }
}
