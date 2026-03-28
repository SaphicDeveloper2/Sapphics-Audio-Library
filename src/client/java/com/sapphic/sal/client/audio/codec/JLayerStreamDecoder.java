package com.sapphic.sal.client.audio.codec;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import com.sapphic.sal.Sapphicsaudiolib;

/**
 * JLayer-based MP3 stream decoder for continuous radio streaming.
 * 
 * <p>Uses JLayer's Bitstream and Decoder classes directly via reflection
 * to decode MP3 frames one at a time. This is necessary because the mp3spi
 * AudioInputStream wrapper doesn't handle infinite streams properly.
 */
public class JLayerStreamDecoder {
    
    private static boolean available = false;
    private static Class<?> bitstreamClass;
    private static Class<?> decoderClass;
    private static Class<?> headerClass;
    private static Class<?> sampleBufferClass;
    
    static {
        try {
            bitstreamClass = Class.forName("javazoom.jl.decoder.Bitstream");
            decoderClass = Class.forName("javazoom.jl.decoder.Decoder");
            headerClass = Class.forName("javazoom.jl.decoder.Header");
            sampleBufferClass = Class.forName("javazoom.jl.decoder.SampleBuffer");
            available = true;
            Sapphicsaudiolib.LOGGER.debug("JLayer stream decoder initialized");
        } catch (ClassNotFoundException e) {
            Sapphicsaudiolib.LOGGER.debug("JLayer not available for streaming: {}", e.getMessage());
        }
    }
    
    /**
     * Checks if JLayer streaming is available.
     */
    public static boolean isAvailable() {
        return available;
    }
    
    /**
     * Creates a streaming AudioInputStream that decodes MP3 frame-by-frame.
     * 
     * @param source The MP3 input stream (continuous radio stream)
     * @return AudioInputStream that produces PCM audio
     * @throws AudioCodec.CodecException if decoding fails
     */
    public static AudioInputStream createStreamingDecoder(InputStream source) throws AudioCodec.CodecException {
        if (!available) {
            throw new AudioCodec.CodecException("JLayer not available");
        }
        
        try {
            return new JLayerAudioInputStream(source);
        } catch (Exception e) {
            throw new AudioCodec.CodecException("Failed to create JLayer decoder: " + e.getMessage(), e);
        }
    }
    
    /**
     * AudioInputStream implementation that decodes MP3 using JLayer frame-by-frame.
     */
    private static class JLayerAudioInputStream extends AudioInputStream {
        
        private final InputStream source;
        private final Object bitstream;
        private final Object decoder;
        
        private final Method readFrameMethod;
        private final Method decodeFrameMethod;
        private final Method getBufferMethod;
        private final Method getBufferLengthMethod;
        private final Method frequencyMethod;
        private final Method modeMethod;
        
        private ByteBuffer pcmBuffer;
        private int sampleRate = 44100;
        private int channels = 2;
        private boolean initialized = false;
        private boolean closed = false;
        
        JLayerAudioInputStream(InputStream source) throws Exception {
            super(new DummyInputStream(), getDefaultFormat(), -1);
            
            this.source = source;
            
            // Create Bitstream
            Constructor<?> bitstreamCtor = bitstreamClass.getConstructor(InputStream.class);
            this.bitstream = bitstreamCtor.newInstance(source);
            
            // Create Decoder
            Constructor<?> decoderCtor = decoderClass.getConstructor();
            this.decoder = decoderCtor.newInstance();
            
            // Get methods
            this.readFrameMethod = bitstreamClass.getMethod("readFrame");
            this.decodeFrameMethod = decoderClass.getMethod("decodeFrame", headerClass, bitstreamClass);
            this.getBufferMethod = sampleBufferClass.getMethod("getBuffer");
            this.getBufferLengthMethod = sampleBufferClass.getMethod("getBufferLength");
            this.frequencyMethod = headerClass.getMethod("frequency");
            this.modeMethod = headerClass.getMethod("mode");
            
            // Decode first frame to get format info
            decodeFirstFrame();
        }
        
        private void decodeFirstFrame() throws Exception {
            Object header = readFrameMethod.invoke(bitstream);
            if (header != null) {
                this.sampleRate = (int) frequencyMethod.invoke(header);
                int mode = (int) modeMethod.invoke(header);
                this.channels = (mode == 3) ? 1 : 2; // mode 3 = single channel
                
                // Decode the frame
                Object output = decodeFrameMethod.invoke(decoder, header, bitstream);
                if (output != null && sampleBufferClass.isInstance(output)) {
                    short[] samples = (short[]) getBufferMethod.invoke(output);
                    int length = (int) getBufferLengthMethod.invoke(output);
                    
                    // Convert to bytes
                    pcmBuffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < length; i++) {
                        pcmBuffer.putShort(samples[i]);
                    }
                    pcmBuffer.flip();
                }
                
                // Close the frame
                Method closeFrameMethod = bitstreamClass.getMethod("closeFrame");
                closeFrameMethod.invoke(bitstream);
                
                initialized = true;
                Sapphicsaudiolib.LOGGER.debug("JLayer initialized: {} Hz, {} channels", sampleRate, channels);
            }
        }
        
        @Override
        public AudioFormat getFormat() {
            return new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2,
                    sampleRate,
                    false
            );
        }
        
        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r == -1 ? -1 : (b[0] & 0xFF);
        }
        
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) return -1;
            
            int totalRead = 0;
            
            while (totalRead < len) {
                // Use buffered data first
                if (pcmBuffer != null && pcmBuffer.hasRemaining()) {
                    int toRead = Math.min(pcmBuffer.remaining(), len - totalRead);
                    pcmBuffer.get(b, off + totalRead, toRead);
                    totalRead += toRead;
                    continue;
                }
                
                // Decode next frame
                try {
                    Object header = readFrameMethod.invoke(bitstream);
                    if (header == null) {
                        // No more frames available right now
                        if (totalRead > 0) {
                            return totalRead;
                        }
                        // Wait a bit for more data
                        Thread.sleep(10);
                        continue;
                    }
                    
                    Object output = decodeFrameMethod.invoke(decoder, header, bitstream);
                    if (output != null && sampleBufferClass.isInstance(output)) {
                        short[] samples = (short[]) getBufferMethod.invoke(output);
                        int length = (int) getBufferLengthMethod.invoke(output);
                        
                        // Convert to bytes
                        pcmBuffer = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < length; i++) {
                            pcmBuffer.putShort(samples[i]);
                        }
                        pcmBuffer.flip();
                    }
                    
                    // Close the frame
                    Method closeFrameMethod = bitstreamClass.getMethod("closeFrame");
                    closeFrameMethod.invoke(bitstream);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Check if it's a decoder exception or end of stream
                    if (e.getCause() != null && e.getCause().getClass().getName().contains("BitstreamException")) {
                        // Likely end of stream or corruption
                        Sapphicsaudiolib.LOGGER.debug("Bitstream exception: {}", e.getCause().getMessage());
                        if (totalRead > 0) return totalRead;
                        return -1;
                    }
                    throw new IOException("JLayer decode error: " + e.getMessage(), e);
                }
            }
            
            return totalRead > 0 ? totalRead : -1;
        }
        
        @Override
        public void close() throws IOException {
            closed = true;
            try {
                Method closeMethod = bitstreamClass.getMethod("close");
                closeMethod.invoke(bitstream);
            } catch (Exception e) {
                // Ignore
            }
            source.close();
        }
        
        private static AudioFormat getDefaultFormat() {
            return new AudioFormat(44100, 16, 2, true, false);
        }
    }
    
    /**
     * Dummy input stream for AudioInputStream constructor.
     */
    private static class DummyInputStream extends InputStream {
        @Override
        public int read() { return -1; }
    }
}
