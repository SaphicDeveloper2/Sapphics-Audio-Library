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
 * AAC/M4A codec implementation using Java Sound API.
 * 
 * <p>Note: Requires an AAC SPI provider on the classpath. Options:
 * <ul>
 *   <li>JAAD (Java Advanced Audio Decoder)</li>
 *   <li>FFmpeg via javaCV</li>
 * </ul>
 * 
 * Add to build.gradle for JAAD:
 * <pre>
 * dependencies {
 *     implementation 'net.sourceforge.jaadec:jaad:0.8.6'
 * }
 * </pre>
 */
public class AacCodec implements AudioCodec {
    
    private static final AacCodec INSTANCE = new AacCodec();
    private static boolean aacSupportAvailable = false;
    
    static {
        checkAacSupport();
    }
    
    private static void checkAacSupport() {
        try {
            // Check if AAC is supported via Java Sound SPI
            for (AudioFileFormat.Type type : AudioSystem.getAudioFileTypes()) {
                String typeName = type.toString().toLowerCase();
                if (typeName.contains("aac") || typeName.contains("m4a")) {
                    aacSupportAvailable = true;
                    break;
                }
            }
            
            if (!aacSupportAvailable) {
                Sapphicsaudiolib.LOGGER.debug("AAC SPI not detected. Add jaad library for AAC support.");
            } else {
                Sapphicsaudiolib.LOGGER.info("AAC codec support available via Java Sound API");
            }
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.debug("Error checking AAC support: {}", e.getMessage());
        }
    }
    
    public static AacCodec getInstance() {
        return INSTANCE;
    }
    
    private AacCodec() {}
    
    @Override
    public String getName() {
        return "AAC";
    }
    
    @Override
    public String[] getExtensions() {
        return new String[]{"aac", "m4a", "mp4"};
    }
    
    @Override
    public String[] getMimeTypes() {
        return new String[]{
            "audio/aac",
            "audio/aacp",
            "audio/mp4",
            "audio/x-m4a"
        };
    }
    
    @Override
    public boolean canDecode(byte[] data) {
        if (data == null || data.length < 12) {
            return false;
        }
        
        // Check for ADTS header (AAC raw)
        if ((data[0] & 0xFF) == 0xFF && ((data[1] & 0xF0) == 0xF0)) {
            return true;
        }
        
        // Check for MP4/M4A container (ftyp box)
        if (data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p') {
            return true;
        }
        
        // Check for ADIF header
        if (data[0] == 'A' && data[1] == 'D' && data[2] == 'I' && data[3] == 'F') {
            return true;
        }
        
        return false;
    }
    
    /**
     * Checks if AAC decoding is available.
     * 
     * @return true if an AAC SPI provider is installed
     */
    public static boolean isAvailable() {
        return aacSupportAvailable;
    }
    
    @Override
    public DecodedAudio decode(byte[] data) throws CodecException {
        if (!aacSupportAvailable) {
            Sapphicsaudiolib.LOGGER.debug("Attempting AAC decode without confirmed SPI support");
        }
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             AudioInputStream aacStream = AudioSystem.getAudioInputStream(bais)) {
            
            AudioFormat sourceFormat = aacStream.getFormat();
            Sapphicsaudiolib.LOGGER.debug("AAC source format: {}", sourceFormat);
            
            // Target format for OpenAL: 16-bit signed PCM
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false // little endian
            );
            
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(targetFormat, aacStream)) {
                
                ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                
                while ((bytesRead = pcmStream.read(buffer)) != -1) {
                    pcmBuffer.write(buffer, 0, bytesRead);
                }
                
                byte[] pcmBytes = pcmBuffer.toByteArray();
                
                ShortBuffer shortBuffer = MemoryUtil.memAllocShort(pcmBytes.length / 2);
                ByteBuffer byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
                
                while (byteBuffer.hasRemaining()) {
                    shortBuffer.put(byteBuffer.getShort());
                }
                shortBuffer.flip();
                
                int channels = (int) targetFormat.getChannels();
                int sampleRate = (int) targetFormat.getSampleRate();
                int sampleCount = shortBuffer.remaining() / channels;
                
                Sapphicsaudiolib.LOGGER.debug("Decoded AAC: {}Hz, {} channels, {} samples",
                        sampleRate, channels, sampleCount);
                
                return new DecodedAudio(shortBuffer, sampleRate, channels, sampleCount);
            }
            
        } catch (UnsupportedAudioFileException e) {
            throw new CodecException("AAC format not supported. Install jaad library.", e);
        } catch (IOException e) {
            throw new CodecException("Failed to read AAC data: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean supportsStreaming() {
        return false;
    }
}
