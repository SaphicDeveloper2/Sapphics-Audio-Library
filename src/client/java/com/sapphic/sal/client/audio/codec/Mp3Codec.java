package com.sapphic.sal.client.audio.codec;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;

import org.lwjgl.system.MemoryUtil;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.AudioDecoder.DecodedAudio;

/**
 * MP3 codec implementation using JavaZoom mp3spi directly.
 * 
 * <p>This implementation manually instantiates the MP3 decoder classes
 * to avoid issues with Java SPI not detecting bundled JARs.
 */
public class Mp3Codec implements AudioCodec {
    
    private static final Mp3Codec INSTANCE = new Mp3Codec();
    private static boolean mp3SupportAvailable = false;
    private static AudioFileReader mp3FileReader = null;
    private static FormatConversionProvider mp3ConversionProvider = null;
    
    static {
        // Manually load mp3spi classes
        initMp3Support();
    }
    
    private static void initMp3Support() {
        try {
            // Directly instantiate the mp3spi classes
            Class<?> fileReaderClass = Class.forName("javazoom.spi.mpeg.sampled.file.MpegAudioFileReader");
            mp3FileReader = (AudioFileReader) fileReaderClass.getDeclaredConstructor().newInstance();
            
            Class<?> conversionClass = Class.forName("javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider");
            mp3ConversionProvider = (FormatConversionProvider) conversionClass.getDeclaredConstructor().newInstance();
            
            mp3SupportAvailable = true;
            Sapphicsaudiolib.LOGGER.info("MP3 codec support initialized via javazoom.spi.mpeg");
            
        } catch (ClassNotFoundException e) {
            Sapphicsaudiolib.LOGGER.warn("MP3 support not available - mp3spi library not found");
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.warn("Failed to initialize MP3 support: {}", e.getMessage());
        }
    }
    
    public static Mp3Codec getInstance() {
        return INSTANCE;
    }
    
    private Mp3Codec() {}
    
    @Override
    public String getName() {
        return "MP3";
    }
    
    @Override
    public String[] getExtensions() {
        return new String[]{"mp3"};
    }
    
    @Override
    public String[] getMimeTypes() {
        return new String[]{
            "audio/mpeg",
            "audio/mp3",
            "audio/x-mpeg-3"
        };
    }
    
    @Override
    public boolean canDecode(byte[] data) {
        if (data == null || data.length < 3) {
            return false;
        }
        
        // Check for MP3 sync word (0xFF 0xFB, 0xFF 0xFA, 0xFF 0xF3, etc.)
        // or ID3 tag header
        if (data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return true; // ID3v2 tag
        }
        
        // Check for MP3 frame sync
        if ((data[0] & 0xFF) == 0xFF && ((data[1] & 0xE0) == 0xE0)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if MP3 decoding is available.
     * 
     * @return true if an MP3 SPI provider is installed
     */
    public static boolean isAvailable() {
        return mp3SupportAvailable;
    }
    
    @Override
    public DecodedAudio decode(byte[] data) throws CodecException {
        if (!mp3SupportAvailable || mp3FileReader == null) {
            throw new CodecException("MP3 support not available. Ensure mp3spi library is included.");
        }
        
        try (InputStream bais = new BufferedInputStream(new ByteArrayInputStream(data))) {
            // Use the manually loaded MP3 file reader
            AudioInputStream mp3Stream = mp3FileReader.getAudioInputStream(bais);
            
            AudioFormat sourceFormat = mp3Stream.getFormat();
            Sapphicsaudiolib.LOGGER.debug("MP3 source format: {}", sourceFormat);
            
            // Target format for OpenAL: 16-bit signed PCM
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16, // 16-bit
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2, // frame size
                    sourceFormat.getSampleRate(),
                    false // little endian for OpenAL
            );
            
            // Use the manually loaded conversion provider
            AudioInputStream pcmStream;
            if (mp3ConversionProvider != null && mp3ConversionProvider.isConversionSupported(targetFormat, sourceFormat)) {
                pcmStream = mp3ConversionProvider.getAudioInputStream(targetFormat, mp3Stream);
            } else {
                // Fallback to AudioSystem
                pcmStream = AudioSystem.getAudioInputStream(targetFormat, mp3Stream);
            }
            
            try {
                // Read all PCM data
                ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                
                while ((bytesRead = pcmStream.read(buffer)) != -1) {
                    pcmBuffer.write(buffer, 0, bytesRead);
                }
                
                byte[] pcmBytes = pcmBuffer.toByteArray();
                
                // Convert to ShortBuffer for OpenAL
                ShortBuffer shortBuffer = MemoryUtil.memAllocShort(pcmBytes.length / 2);
                ByteBuffer byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
                
                while (byteBuffer.hasRemaining()) {
                    shortBuffer.put(byteBuffer.getShort());
                }
                shortBuffer.flip();
                
                int channels = (int) targetFormat.getChannels();
                int sampleRate = (int) targetFormat.getSampleRate();
                int sampleCount = shortBuffer.remaining() / channels;
                
                Sapphicsaudiolib.LOGGER.debug("Decoded MP3: {}Hz, {} channels, {} samples",
                        sampleRate, channels, sampleCount);
                
                return new DecodedAudio(shortBuffer, sampleRate, channels, sampleCount);
            } finally {
                pcmStream.close();
                mp3Stream.close();
            }
            
        } catch (UnsupportedAudioFileException e) {
            throw new CodecException("MP3 format not supported: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new CodecException("Failed to read MP3 data: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gets a PCM AudioInputStream from an MP3 input stream.
     * This is used for streaming decode.
     * 
     * @param stream The MP3 input stream
     * @return AudioInputStream in PCM format
     * @throws CodecException if decoding fails
     */
    public static AudioInputStream getStreamingAudioInputStream(InputStream stream) throws CodecException {
        if (!mp3SupportAvailable || mp3FileReader == null) {
            throw new CodecException("MP3 support not available");
        }
        
        try {
            InputStream buffered = new BufferedInputStream(stream, 65536);
            AudioInputStream mp3Stream = mp3FileReader.getAudioInputStream(buffered);
            AudioFormat sourceFormat = mp3Stream.getFormat();
            
            Sapphicsaudiolib.LOGGER.debug("MP3 stream format: {}", sourceFormat);
            
            // Target format for OpenAL: 16-bit signed PCM
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            
            // Convert to PCM
            if (mp3ConversionProvider != null && mp3ConversionProvider.isConversionSupported(targetFormat, sourceFormat)) {
                return mp3ConversionProvider.getAudioInputStream(targetFormat, mp3Stream);
            } else {
                return AudioSystem.getAudioInputStream(targetFormat, mp3Stream);
            }
            
        } catch (UnsupportedAudioFileException e) {
            throw new CodecException("Not a valid MP3 stream: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new CodecException("Failed to read MP3 stream: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean supportsStreaming() {
        return mp3SupportAvailable;
    }
}
