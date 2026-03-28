package com.sapphic.sal.client.audio.codec;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.audio.AudioDecoder;
import com.sapphic.sal.client.audio.AudioDecoder.DecodedAudio;
import com.sapphic.sal.client.audio.AudioDecoder.AudioDecoderException;

/**
 * OGG Vorbis codec implementation using LWJGL STB Vorbis.
 * This is the primary/native codec for SapphicsAudioLib.
 */
public class OggVorbisCodec implements AudioCodec {
    
    private static final OggVorbisCodec INSTANCE = new OggVorbisCodec();
    
    public static OggVorbisCodec getInstance() {
        return INSTANCE;
    }
    
    private OggVorbisCodec() {}
    
    @Override
    public String getName() {
        return "OGG";
    }
    
    @Override
    public String[] getExtensions() {
        return new String[]{"ogg", "oga"};
    }
    
    @Override
    public String[] getMimeTypes() {
        return new String[]{
            "audio/ogg",
            "audio/vorbis",
            "application/ogg"
        };
    }
    
    @Override
    public boolean canDecode(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        // Check for OGG magic bytes: "OggS"
        return data[0] == 'O' && data[1] == 'g' && data[2] == 'g' && data[3] == 'S';
    }
    
    @Override
    public DecodedAudio decode(byte[] data) throws CodecException {
        try {
            return AudioDecoder.decode(data);
        } catch (AudioDecoderException e) {
            throw new CodecException("Failed to decode OGG Vorbis: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean supportsStreaming() {
        return false; // STB Vorbis decode_memory is all-at-once
    }
}
