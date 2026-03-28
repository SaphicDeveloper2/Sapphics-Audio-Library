package com.sapphic.sal.client.audio.codec;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.AudioDecoder.DecodedAudio;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * MP3 codec implementation using Java Sound API.
 * 
 * <p>Note: Requires an MP3 SPI provider on the classpath. Popular options:
 * <ul>
 *   <li>JLayer MP3SPI (mp3spi + jlayer + tritonus-share)</li>
 *   <li>JavaZoom's BasicPlayer library</li>
 * </ul>
 * 
 * Add to build.gradle:
 * <pre>
 * dependencies {
 *     implementation 'javazoom:jlayer:1.0.1'
 *     implementation 'com.googlecode.soundlibs:mp3spi:1.9.5.4'
 *     implementation 'com.googlecode.soundlibs:tritonus-share:0.3.7.4'
 * }
 * </pre>
 */
public class Mp3Codec implements AudioCodec {
    
    private static final Mp3Codec INSTANCE = new Mp3Codec();
    private static boolean mp3SupportAvailable = false;
    
    static {
        // Check if MP3 support is available
        checkMp3Support();
    }
    
    private static void checkMp3Support() {
        try {
            // Try to check if MP3 is registered
            for (AudioFileFormat.Type type : AudioSystem.getAudioFileTypes()) {
                if (type.toString().toLowerCase().contains("mp3")) {
                    mp3SupportAvailable = true;
                    break;
                }
            }
            
            // Also check available format converters
            AudioFormat mp3Format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    44100, 16, 2, 4, 44100, false
            );
            // This doesn't guarantee MP3 support but is a partial check
            
            if (!mp3SupportAvailable) {
                Sapphicsaudiolib.LOGGER.warn("MP3 SPI not detected. Add mp3spi library for MP3 support.");
            } else {
                Sapphicsaudiolib.LOGGER.info("MP3 codec support available via Java Sound API");
            }
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.debug("Error checking MP3 support: {}", e.getMessage());
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
        if (!mp3SupportAvailable) {
            // Try anyways - the SPI check might have missed something
            Sapphicsaudiolib.LOGGER.debug("Attempting MP3 decode without confirmed SPI support");
        }
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             AudioInputStream mp3Stream = AudioSystem.getAudioInputStream(bais)) {
            
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
            
            // Convert to PCM
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, mp3Stream)) {
                
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
            }
            
        } catch (UnsupportedAudioFileException e) {
            throw new CodecException("MP3 format not supported. Install mp3spi library.", e);
        } catch (IOException e) {
            throw new CodecException("Failed to read MP3 data: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean supportsStreaming() {
        return false; // Basic implementation doesn't support streaming yet
    }
}
