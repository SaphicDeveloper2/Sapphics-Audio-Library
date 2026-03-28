package com.sapphic.sal.client.audio;

import com.sapphic.sal.Sapphicsaudiolib;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Audio decoder using LWJGL's STB Vorbis library.
 * Converts .ogg Vorbis audio files into raw PCM data for OpenAL playback.
 */
public class AudioDecoder {
    
    /**
     * Result of decoding an audio file.
     */
    public static class DecodedAudio {
        private final ShortBuffer pcmData;
        private final int sampleRate;
        private final int channels;
        private final int sampleCount;
        private boolean freed = false;
        
        public DecodedAudio(ShortBuffer pcmData, int sampleRate, int channels, int sampleCount) {
            this.pcmData = pcmData;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.sampleCount = sampleCount;
        }
        
        /**
         * @return The raw PCM audio data as 16-bit signed integers
         */
        public ShortBuffer getPcmData() {
            return pcmData;
        }
        
        /**
         * @return Sample rate in Hz (e.g., 44100, 48000)
         */
        public int getSampleRate() {
            return sampleRate;
        }
        
        /**
         * @return Number of audio channels (1 = mono, 2 = stereo)
         */
        public int getChannels() {
            return channels;
        }
        
        /**
         * @return Total number of audio samples
         */
        public int getSampleCount() {
            return sampleCount;
        }
        
        /**
         * @return Duration in seconds
         */
        public float getDurationSeconds() {
            return (float) sampleCount / sampleRate;
        }
        
        /**
         * @return The OpenAL format constant for this audio
         */
        public int getOpenALFormat() {
            return channels == 1 
                    ? org.lwjgl.openal.AL10.AL_FORMAT_MONO16 
                    : org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;
        }
        
        /**
         * Frees the native memory used by the PCM data.
         * MUST be called when done with this decoded audio!
         */
        public void free() {
            if (!freed && pcmData != null) {
                MemoryUtil.memFree(pcmData);
                freed = true;
            }
        }
        
        /**
         * @return Whether this audio data has been freed
         */
        public boolean isFreed() {
            return freed;
        }
    }
    
    /**
     * Decodes .ogg Vorbis audio data from memory into raw PCM.
     * 
     * @param oggData The raw .ogg file bytes
     * @return DecodedAudio containing PCM data and metadata
     * @throws AudioDecoderException if decoding fails
     */
    public static DecodedAudio decode(byte[] oggData) throws AudioDecoderException {
        if (oggData == null || oggData.length == 0) {
            throw new AudioDecoderException("Audio data is null or empty");
        }
        
        // Copy data to a direct ByteBuffer for STB Vorbis
        ByteBuffer oggBuffer = MemoryUtil.memAlloc(oggData.length);
        oggBuffer.put(oggData);
        oggBuffer.flip();
        
        ShortBuffer pcmData = null;
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errorBuffer = stack.mallocInt(1);
            IntBuffer channelsBuffer = stack.mallocInt(1);
            IntBuffer sampleRateBuffer = stack.mallocInt(1);
            
            // Decode the entire file at once
            pcmData = STBVorbis.stb_vorbis_decode_memory(oggBuffer, channelsBuffer, sampleRateBuffer);
            
            if (pcmData == null) {
                // Try to get more specific error info
                long decoder = STBVorbis.stb_vorbis_open_memory(oggBuffer, errorBuffer, null);
                int error = errorBuffer.get(0);
                if (decoder != 0) {
                    STBVorbis.stb_vorbis_close(decoder);
                }
                throw new AudioDecoderException("Failed to decode Vorbis data, error code: " + error);
            }
            
            int channels = channelsBuffer.get(0);
            int sampleRate = sampleRateBuffer.get(0);
            int sampleCount = pcmData.remaining() / channels;
            
            Sapphicsaudiolib.LOGGER.debug("Decoded audio: {}Hz, {} channels, {} samples, {:.2f}s",
                    sampleRate, channels, sampleCount, (float) sampleCount / sampleRate);
            
            return new DecodedAudio(pcmData, sampleRate, channels, sampleCount);
            
        } finally {
            // Free the input buffer
            MemoryUtil.memFree(oggBuffer);
        }
    }
    
    /**
     * Decodes audio using streaming mode for very large files.
     * This method decodes in chunks, which uses less peak memory.
     * 
     * @param oggData The raw .ogg file bytes
     * @param maxDurationSeconds Maximum duration to decode (0 = all)
     * @return DecodedAudio containing PCM data
     * @throws AudioDecoderException if decoding fails
     */
    public static DecodedAudio decodeStreaming(byte[] oggData, float maxDurationSeconds) throws AudioDecoderException {
        if (oggData == null || oggData.length == 0) {
            throw new AudioDecoderException("Audio data is null or empty");
        }
        
        ByteBuffer oggBuffer = MemoryUtil.memAlloc(oggData.length);
        oggBuffer.put(oggData);
        oggBuffer.flip();
        
        long decoder = 0;
        ShortBuffer finalPcm = null;
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errorBuffer = stack.mallocInt(1);
            
            // Open the decoder
            decoder = STBVorbis.stb_vorbis_open_memory(oggBuffer, errorBuffer, null);
            if (decoder == 0) {
                throw new AudioDecoderException("Failed to open Vorbis decoder, error: " + errorBuffer.get(0));
            }
            
            // Get audio info
            try (STBVorbisInfo info = STBVorbisInfo.malloc(stack)) {
                STBVorbis.stb_vorbis_get_info(decoder, info);
                
                int channels = info.channels();
                int sampleRate = info.sample_rate();
                int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(decoder);
                
                // Apply duration limit if specified
                if (maxDurationSeconds > 0) {
                    int maxSamples = (int) (maxDurationSeconds * sampleRate);
                    totalSamples = Math.min(totalSamples, maxSamples);
                }
                
                // Allocate output buffer
                finalPcm = MemoryUtil.memAllocShort(totalSamples * channels);
                
                // Decode in chunks
                int samplesRemaining = totalSamples;
                int chunkSize = 4096;
                ShortBuffer tempBuffer = MemoryUtil.memAllocShort(chunkSize * channels);
                
                try {
                    while (samplesRemaining > 0) {
                        int samplesToRead = Math.min(chunkSize, samplesRemaining);
                        tempBuffer.clear();
                        
                        int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                                decoder, channels, tempBuffer);
                        
                        if (samplesRead <= 0) break;
                        
                        // Copy to final buffer
                        tempBuffer.limit(samplesRead * channels);
                        finalPcm.put(tempBuffer);
                        
                        samplesRemaining -= samplesRead;
                    }
                } finally {
                    MemoryUtil.memFree(tempBuffer);
                }
                
                finalPcm.flip();
                int actualSamples = finalPcm.remaining() / channels;
                
                return new DecodedAudio(finalPcm, sampleRate, channels, actualSamples);
            }
            
        } catch (Exception e) {
            // Free PCM on error
            if (finalPcm != null) {
                MemoryUtil.memFree(finalPcm);
            }
            throw new AudioDecoderException("Failed to decode audio: " + e.getMessage(), e);
        } finally {
            if (decoder != 0) {
                STBVorbis.stb_vorbis_close(decoder);
            }
            MemoryUtil.memFree(oggBuffer);
        }
    }
    
    /**
     * Gets information about an audio file without fully decoding it.
     * 
     * @param oggData The raw .ogg file bytes
     * @return AudioInfo with metadata
     * @throws AudioDecoderException if reading fails
     */
    public static AudioInfo getInfo(byte[] oggData) throws AudioDecoderException {
        if (oggData == null || oggData.length == 0) {
            throw new AudioDecoderException("Audio data is null or empty");
        }
        
        ByteBuffer oggBuffer = MemoryUtil.memAlloc(oggData.length);
        oggBuffer.put(oggData);
        oggBuffer.flip();
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer errorBuffer = stack.mallocInt(1);
            
            long decoder = STBVorbis.stb_vorbis_open_memory(oggBuffer, errorBuffer, null);
            if (decoder == 0) {
                throw new AudioDecoderException("Failed to open Vorbis file, error: " + errorBuffer.get(0));
            }
            
            try (STBVorbisInfo info = STBVorbisInfo.malloc(stack)) {
                STBVorbis.stb_vorbis_get_info(decoder, info);
                
                int channels = info.channels();
                int sampleRate = info.sample_rate();
                int totalSamples = STBVorbis.stb_vorbis_stream_length_in_samples(decoder);
                float duration = STBVorbis.stb_vorbis_stream_length_in_seconds(decoder);
                
                STBVorbis.stb_vorbis_close(decoder);
                
                return new AudioInfo(sampleRate, channels, totalSamples, duration);
            }
        } finally {
            MemoryUtil.memFree(oggBuffer);
        }
    }
    
    /**
     * Information about an audio file.
     */
    public record AudioInfo(int sampleRate, int channels, int totalSamples, float durationSeconds) {}
    
    /**
     * Exception thrown when audio decoding fails.
     */
    public static class AudioDecoderException extends Exception {
        public AudioDecoderException(String message) {
            super(message);
        }
        
        public AudioDecoderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
