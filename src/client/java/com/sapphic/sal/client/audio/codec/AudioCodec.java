package com.sapphic.sal.client.audio.codec;

import com.sapphic.sal.client.audio.AudioDecoder.DecodedAudio;

/**
 * Interface for audio codec decoders.
 * Implementations handle specific audio formats (OGG, MP3, AAC, etc.).
 */
public interface AudioCodec {
    
    /**
     * Gets the codec name/identifier.
     * 
     * @return Codec name (e.g., "OGG", "MP3", "AAC")
     */
    String getName();
    
    /**
     * Gets the file extensions supported by this codec.
     * 
     * @return Array of extensions (e.g., ["ogg"], ["mp3"], ["aac", "m4a"])
     */
    String[] getExtensions();
    
    /**
     * Gets the MIME types supported by this codec.
     * 
     * @return Array of MIME types
     */
    String[] getMimeTypes();
    
    /**
     * Checks if this codec can decode the given data.
     * Should check magic bytes/header to verify format.
     * 
     * @param data Audio data to check
     * @return true if this codec can decode it
     */
    boolean canDecode(byte[] data);
    
    /**
     * Decodes audio data into PCM format for OpenAL playback.
     * 
     * @param data Raw audio data
     * @return Decoded PCM audio
     * @throws CodecException if decoding fails
     */
    DecodedAudio decode(byte[] data) throws CodecException;
    
    /**
     * Checks if this codec supports streaming (partial decoding).
     * 
     * @return true if streaming is supported
     */
    default boolean supportsStreaming() {
        return false;
    }
    
    /**
     * Creates a streaming decoder for continuous audio.
     * Only available if supportsStreaming() returns true.
     * 
     * @return A streaming decoder instance
     * @throws UnsupportedOperationException if streaming not supported
     */
    default StreamingDecoder createStreamingDecoder() {
        throw new UnsupportedOperationException("Streaming not supported by " + getName());
    }
    
    /**
     * Interface for streaming decoders that can decode audio in chunks.
     */
    interface StreamingDecoder extends AutoCloseable {
        
        /**
         * Feeds data to the decoder.
         * 
         * @param data Chunk of audio data
         */
        void feed(byte[] data);
        
        /**
         * Decodes available audio data.
         * 
         * @return Decoded audio, or null if not enough data yet
         */
        DecodedAudio decodeAvailable();
        
        /**
         * Gets the sample rate (once known).
         * 
         * @return Sample rate, or -1 if not yet determined
         */
        int getSampleRate();
        
        /**
         * Gets the channel count (once known).
         * 
         * @return Channels, or -1 if not yet determined
         */
        int getChannels();
        
        /**
         * Signals end of stream and flushes remaining data.
         * 
         * @return Final decoded audio, or null if none
         */
        DecodedAudio finish();
        
        @Override
        void close();
    }
    
    /**
     * Exception thrown when codec decoding fails.
     */
    class CodecException extends Exception {
        public CodecException(String message) {
            super(message);
        }
        
        public CodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
