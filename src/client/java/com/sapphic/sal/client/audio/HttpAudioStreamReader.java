package com.sapphic.sal.client.audio;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import org.jetbrains.annotations.Nullable;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.codec.CodecRegistry;

/**
 * Reads audio from HTTP streams and feeds decoded PCM data to a {@link StreamingAudioPlayer.StreamingSession}.
 * 
 * <p>This class handles:
 * <ul>
 *   <li>HTTP connection with proper streaming headers</li>
 *   <li>Automatic codec detection (MP3, OGG, AAC, etc.)</li>
 *   <li>Continuous decoding and feeding to the audio player</li>
 *   <li>HTTP redirects (up to 5)</li>
 * </ul>
 */
public class HttpAudioStreamReader {
    
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 30000;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 4096;
    private static final int TARGET_SAMPLE_RATE = 48000;
    
    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SapphicsAudioLib-HttpStream");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Represents an active HTTP streaming connection.
     */
    public static class StreamConnection {
        private final String url;
        private final StreamingAudioPlayer.StreamingSession session;
        private final AtomicBoolean running = new AtomicBoolean(true);
        
        @Nullable
        private HttpURLConnection connection;
        @Nullable
        private InputStream inputStream;
        @Nullable
        private Runnable onError;
        @Nullable
        private Runnable onComplete;
        
        StreamConnection(String url, StreamingAudioPlayer.StreamingSession session) {
            this.url = url;
            this.session = session;
        }
        
        /** Sets a callback for when an error occurs */
        public StreamConnection onError(Runnable callback) {
            this.onError = callback;
            return this;
        }
        
        /** Sets a callback for when the stream completes or closes */
        public StreamConnection onComplete(Runnable callback) {
            this.onComplete = callback;
            return this;
        }
        
        /** Stops the streaming connection */
        public void stop() {
            running.set(false);
            session.stop();
            closeQuietly();
        }
        
        /** Checks if the connection is still active */
        public boolean isRunning() {
            return running.get() && session.isRunning();
        }
        
        /** Gets the underlying streaming session */
        public StreamingAudioPlayer.StreamingSession getSession() {
            return session;
        }
        
        private void closeQuietly() {
            try {
                if (inputStream != null) inputStream.close();
            } catch (Exception ignored) {}
            try {
                if (connection != null) connection.disconnect();
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * Starts streaming from an HTTP URL and feeding to an audio session.
     * 
     * @param url The HTTP/HTTPS URL of the audio stream
     * @param volume Initial playback volume (0.0 to 1.0)
     * @return A StreamConnection for controlling the stream, or null if failed to start
     */
    @Nullable
    public static StreamConnection startStream(String url, float volume) {
        // Create the audio session
        StreamingAudioPlayer.StreamingSession session = 
                StreamingAudioPlayer.createSession(null, volume, 64f);
        
        if (session == null) {
            Sapphicsaudiolib.LOGGER.error("Failed to create streaming session for {}", url);
            return null;
        }
        
        StreamConnection connection = new StreamConnection(url, session);
        
        // Start the streaming thread
        executor.submit(() -> runStreamLoop(connection));
        
        return connection;
    }
    
    /**
     * The main streaming loop - connects, decodes, and feeds audio data.
     */
    private static void runStreamLoop(StreamConnection conn) {
        Sapphicsaudiolib.LOGGER.info("Starting HTTP stream from {}", conn.url);
        
        try {
            // Open HTTP connection with redirect handling
            HttpURLConnection httpConn = openConnection(conn.url);
            if (httpConn == null) {
                throw new RuntimeException("Failed to establish connection");
            }
            
            conn.connection = httpConn;
            
            // Get content type for codec detection
            String contentType = httpConn.getContentType();
            Sapphicsaudiolib.LOGGER.debug("Stream content type: {}", contentType);
            
            // Get input stream
            InputStream rawStream = new BufferedInputStream(httpConn.getInputStream(), 65536);
            conn.inputStream = rawStream;
            
            // Try to get a codec for this content type
            AudioInputStream audioInputStream = decodeStream(rawStream, contentType, conn.url);
            if (audioInputStream == null) {
                throw new RuntimeException("No codec available for content type: " + contentType);
            }
            
            // Get audio format and prepare for resampling
            AudioFormat format = audioInputStream.getFormat();
            Sapphicsaudiolib.LOGGER.info("Audio format: {} Hz, {} channels, {} bits", 
                    format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());
            
            // Read and feed audio data
            feedAudio(conn, audioInputStream, format);
            
            // Stream completed normally
            Sapphicsaudiolib.LOGGER.info("Stream completed: {}", conn.url);
            if (conn.onComplete != null) {
                conn.onComplete.run();
            }
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Error streaming from {}: {}", conn.url, e.getMessage());
            if (conn.onError != null) {
                conn.onError.run();
            }
        } finally {
            conn.running.set(false);
            conn.session.stop();
            conn.closeQuietly();
        }
    }
    
    /**
     * Opens an HTTP connection, following redirects.
     */
    @Nullable
    private static HttpURLConnection openConnection(String urlString) {
        int redirectCount = 0;
        String currentUrl = urlString;
        
        while (redirectCount < MAX_REDIRECTS) {
            try {
                URL url = URI.create(currentUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "SapphicsAudioLib/1.0");
                conn.setRequestProperty("Accept", "audio/*");
                conn.setRequestProperty("Icy-MetaData", "0");
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    return conn;
                } else if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                           responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                           responseCode == 307 || responseCode == 308) {
                    // Follow redirect
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        Sapphicsaudiolib.LOGGER.error("Redirect with no location header");
                        return null;
                    }
                    currentUrl = location;
                    redirectCount++;
                    conn.disconnect();
                    Sapphicsaudiolib.LOGGER.debug("Following redirect to {}", location);
                } else {
                    Sapphicsaudiolib.LOGGER.error("HTTP error {}: {}", responseCode, conn.getResponseMessage());
                    conn.disconnect();
                    return null;
                }
                
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.error("Connection error: {}", e.getMessage());
                return null;
            }
        }
        
        Sapphicsaudiolib.LOGGER.error("Too many redirects ({})", redirectCount);
        return null;
    }
    
    /**
     * Attempts to decode the stream using available codecs.
     */
    @Nullable
    private static AudioInputStream decodeStream(InputStream stream, @Nullable String contentType, String url) {
        // Determine format from content type or URL extension
        String format = detectFormat(contentType, url);
        
        Sapphicsaudiolib.LOGGER.debug("Detected audio format: {}", format);
        
        try {
            // Use CodecRegistry to get decoder
            return CodecRegistry.decode(stream, format);
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Failed to decode stream as {}: {}", format, e.getMessage());
            
            // Try fallback formats
            String[] fallbacks = {"mp3", "ogg", "aac"};
            for (String fallback : fallbacks) {
                if (!fallback.equals(format)) {
                    try {
                        Sapphicsaudiolib.LOGGER.debug("Trying fallback codec: {}", fallback);
                        return CodecRegistry.decode(stream, fallback);
                    } catch (Exception ignored) {}
                }
            }
            
            return null;
        }
    }
    
    /**
     * Detects audio format from content type and URL.
     */
    private static String detectFormat(@Nullable String contentType, String url) {
        // Check content type first
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.contains("mpeg") || ct.contains("mp3")) return "mp3";
            if (ct.contains("ogg") || ct.contains("vorbis") || ct.contains("opus")) return "ogg";
            if (ct.contains("aac") || ct.contains("m4a") || ct.contains("mp4")) return "aac";
            if (ct.contains("wav") || ct.contains("wave")) return "wav";
            if (ct.contains("flac")) return "flac";
        }
        
        // Check URL extension
        String lower = url.toLowerCase();
        if (lower.contains(".mp3")) return "mp3";
        if (lower.contains(".ogg") || lower.contains(".opus")) return "ogg";
        if (lower.contains(".aac") || lower.contains(".m4a")) return "aac";
        if (lower.contains(".wav")) return "wav";
        if (lower.contains(".flac")) return "flac";
        
        // Default to mp3 as most common stream format
        return "mp3";
    }
    
    /**
     * Reads decoded audio and feeds it to the streaming session.
     * For continuous radio streams, this keeps reading until explicitly stopped.
     */
    private static void feedAudio(StreamConnection conn, AudioInputStream audioStream, AudioFormat format) 
            throws Exception {
        
        int channels = format.getChannels();
        int sampleRate = (int) format.getSampleRate();
        int bytesPerSample = format.getSampleSizeInBits() / 8;
        boolean isSigned = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED;
        boolean isBigEndian = format.isBigEndian();
        
        // Buffer for reading raw bytes
        byte[] buffer = new byte[BUFFER_SIZE * bytesPerSample * channels];
        
        int consecutiveEmptyReads = 0;
        final int MAX_EMPTY_READS = 100; // Allow some empty reads before giving up
        
        while (conn.isRunning()) {
            int bytesRead;
            try {
                bytesRead = audioStream.read(buffer);
            } catch (Exception e) {
                Sapphicsaudiolib.LOGGER.debug("Stream read error: {}", e.getMessage());
                break;
            }
            
            if (bytesRead <= 0) {
                // For continuous streams, don't immediately exit on empty read
                consecutiveEmptyReads++;
                if (consecutiveEmptyReads >= MAX_EMPTY_READS) {
                    Sapphicsaudiolib.LOGGER.debug("Stream appears to have ended after {} empty reads", consecutiveEmptyReads);
                    break;
                }
                // Wait a bit for more data to arrive
                Thread.sleep(50);
                continue;
            }
            
            // Reset counter on successful read
            consecutiveEmptyReads = 0;
            
            // Convert to mono 16-bit PCM for OpenAL
            short[] pcmData = convertToPCM16(buffer, bytesRead, channels, bytesPerSample, isSigned, isBigEndian);
            
            // Apply sample rate conversion if needed (simple resampling)
            if (sampleRate != TARGET_SAMPLE_RATE) {
                pcmData = resample(pcmData, sampleRate, TARGET_SAMPLE_RATE);
            }
            
            // Queue the audio data - retry if queue is full
            int retries = 0;
            while (!conn.session.queueAudio(pcmData) && conn.isRunning() && retries < 50) {
                Thread.sleep(20);
                retries++;
            }
        }
    }
    
    /**
     * Converts raw audio bytes to mono 16-bit PCM shorts.
     */
    private static short[] convertToPCM16(byte[] data, int length, int channels, 
            int bytesPerSample, boolean isSigned, boolean isBigEndian) {
        
        int samples = length / (bytesPerSample * channels);
        short[] output = new short[samples];
        
        for (int i = 0; i < samples; i++) {
            int offset = i * bytesPerSample * channels;
            
            // Mix channels to mono
            long monoSample = 0;
            for (int ch = 0; ch < channels; ch++) {
                int chOffset = offset + ch * bytesPerSample;
                int sample = readSample(data, chOffset, bytesPerSample, isSigned, isBigEndian);
                monoSample += sample;
            }
            monoSample /= channels;
            
            // Convert to 16-bit range
            if (bytesPerSample == 1) {
                monoSample = monoSample << 8;
            } else if (bytesPerSample == 4) {
                monoSample = monoSample >> 16;
            }
            
            // Clamp to short range
            output[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, monoSample));
        }
        
        return output;
    }
    
    /**
     * Reads a single sample from byte array.
     */
    private static int readSample(byte[] data, int offset, int bytesPerSample, 
            boolean isSigned, boolean isBigEndian) {
        if (offset + bytesPerSample > data.length) return 0;
        
        int value = 0;
        if (isBigEndian) {
            for (int i = 0; i < bytesPerSample; i++) {
                value = (value << 8) | (data[offset + i] & 0xFF);
            }
        } else {
            for (int i = bytesPerSample - 1; i >= 0; i--) {
                value = (value << 8) | (data[offset + i] & 0xFF);
            }
        }
        
        // Sign extend if needed
        if (isSigned && bytesPerSample < 4) {
            int signBit = 1 << (bytesPerSample * 8 - 1);
            if ((value & signBit) != 0) {
                value |= (-1 << (bytesPerSample * 8));
            }
        }
        
        return value;
    }
    
    /**
     * Simple linear resampling.
     */
    private static short[] resample(short[] input, int srcRate, int dstRate) {
        if (srcRate == dstRate) return input;
        
        double ratio = (double) srcRate / dstRate;
        int outputLength = (int) (input.length / ratio);
        short[] output = new short[outputLength];
        
        for (int i = 0; i < outputLength; i++) {
            double srcPos = i * ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;
            
            if (srcIndex + 1 < input.length) {
                // Linear interpolation
                output[i] = (short) (input[srcIndex] * (1 - frac) + input[srcIndex + 1] * frac);
            } else {
                output[i] = srcIndex < input.length ? input[srcIndex] : 0;
            }
        }
        
        return output;
    }
}
