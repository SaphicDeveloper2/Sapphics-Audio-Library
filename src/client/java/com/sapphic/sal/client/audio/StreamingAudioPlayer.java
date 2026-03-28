package com.sapphic.sal.client.audio;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import com.sapphic.sal.Sapphicsaudiolib;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * Streaming audio player using OpenAL buffer queuing.
 * Based on Simple Voice Chat's approach - uses a pool of buffers that
 * are continuously cycled to enable smooth streaming playback.
 * 
 * <p>Unlike {@link AudioEngine} which loads all audio at once, this class
 * supports continuous streaming by queuing small chunks of audio data.</p>
 */
public class StreamingAudioPlayer {
    
    /** Number of buffers in the queue pool */
    private static final int BUFFER_COUNT = 32;
    
    /** Size of each buffer in samples (mono 16-bit = 2 bytes per sample) */
    private static final int BUFFER_SAMPLE_SIZE = 4096;
    
    /** Sample rate for playback */
    private static final int SAMPLE_RATE = 48000;
    
    /** Pre-fill this many buffers before starting playback */
    private static final int PREFILL_BUFFERS = 4;
    
    /** Active streaming sessions */
    private static final Map<UUID, StreamingSession> sessions = new ConcurrentHashMap<>();
    
    /** Executor for audio operations */
    private static final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SapphicsAudioLib-StreamPlayer");
        t.setDaemon(true);
        return t;
    });
    
    /**
     * Represents an active streaming audio session.
     */
    public static class StreamingSession {
        private final UUID sessionId;
        private final int source;
        private final int[] buffers;
        private final AtomicInteger bufferIndex = new AtomicInteger(0);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final BlockingQueue<short[]> audioQueue = new LinkedBlockingQueue<>(64);
        
        private volatile float volume = 1.0f;
        private volatile Vec3d position;
        private volatile float maxDistance = 64.0f;
        
        StreamingSession(UUID sessionId, int source, int[] buffers) {
            this.sessionId = sessionId;
            this.source = source;
            this.buffers = buffers;
        }
        
        /** Gets the session ID */
        public UUID getSessionId() {
            return sessionId;
        }
        
        /** Checks if the session is still active */
        public boolean isRunning() {
            return running.get();
        }
        
        /** Stops the streaming session */
        public void stop() {
            running.set(false);
        }
        
        /** Sets the playback volume */
        public void setVolume(float volume) {
            this.volume = Math.max(0f, Math.min(1f, volume));
            if (source > 0) {
                AL10.alSourcef(source, AL10.AL_GAIN, this.volume);
            }
        }
        
        /** Gets the current volume */
        public float getVolume() {
            return volume;
        }
        
        /** Sets the 3D position (null for non-positional) */
        public void setPosition(@Nullable Vec3d position) {
            this.position = position;
        }
        
        /** Gets the 3D position */
        @Nullable
        public Vec3d getPosition() {
            return position;
        }
        
        /**
         * Queues audio data for playback.
         * 
         * @param pcmData 16-bit PCM audio data (mono)
         * @return true if queued, false if queue is full or session stopped
         */
        public boolean queueAudio(short[] pcmData) {
            if (!running.get()) return false;
            return audioQueue.offer(pcmData);
        }
        
        /**
         * Gets the number of buffers waiting to be played.
         */
        public int getQueuedCount() {
            return audioQueue.size();
        }
    }
    
    /**
     * Creates a new streaming session.
     * 
     * @param position 3D position for the audio (null for non-positional/headphone audio)
     * @param volume Initial volume (0.0 to 1.0)
     * @param maxDistance Maximum hearing distance in blocks
     * @return The streaming session, or null if creation failed
     */
    @Nullable
    public static StreamingSession createSession(@Nullable Vec3d position, float volume, float maxDistance) {
        UUID sessionId = UUID.randomUUID();
        
        try {
            // Generate OpenAL source
            int source = AL10.alGenSources();
            checkALError("alGenSources");
            
            // Generate buffer pool
            int[] buffers = new int[BUFFER_COUNT];
            AL10.alGenBuffers(buffers);
            checkALError("alGenBuffers");
            
            // Configure source
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0f, Math.min(1f, volume)));
            
            // Distance attenuation
            AL11.alDistanceModel(AL11.AL_LINEAR_DISTANCE);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, maxDistance);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, maxDistance / 2f);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0f);
            
            if (position != null) {
                // 3D positional audio
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                AL10.alSource3f(source, AL10.AL_POSITION, 
                        (float) position.x, (float) position.y, (float) position.z);
            } else {
                // Non-positional (headphone) audio
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
            }
            checkALError("source config");
            
            StreamingSession session = new StreamingSession(sessionId, source, buffers);
            session.position = position;
            session.maxDistance = maxDistance;
            session.volume = volume;
            
            sessions.put(sessionId, session);
            
            // Start the playback thread
            executor.submit(() -> runPlaybackLoop(session));
            
            Sapphicsaudiolib.LOGGER.debug("Created streaming session {}", sessionId);
            return session;
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Failed to create streaming session", e);
            return null;
        }
    }
    
    /**
     * The main playback loop - continuously feeds audio from queue to OpenAL buffers.
     */
    private static void runPlaybackLoop(StreamingSession session) {
        try {
            Sapphicsaudiolib.LOGGER.debug("Starting playback loop for session {}", session.sessionId);
            
            while (session.running.get()) {
                // Remove processed buffers
                removeProcessedBuffers(session);
                
                // Check if we need to restart playback
                int state = AL10.alGetSourcei(session.source, AL10.AL_SOURCE_STATE);
                boolean stopped = (state == AL10.AL_STOPPED || state == AL10.AL_INITIAL);
                
                // Get queued buffer count
                int queuedBuffers = AL10.alGetSourcei(session.source, AL10.AL_BUFFERS_QUEUED);
                
                // If stopped and we have data, pre-fill buffers before starting
                if (stopped && !session.audioQueue.isEmpty()) {
                    int prefillCount = Math.min(PREFILL_BUFFERS, session.audioQueue.size());
                    for (int i = 0; i < prefillCount; i++) {
                        short[] data = session.audioQueue.poll();
                        if (data != null) {
                            queueBuffer(session, data);
                        }
                    }
                    
                    // Start playback
                    AL10.alSourcePlay(session.source);
                    session.started.set(true);
                    Sapphicsaudiolib.LOGGER.debug("Started playback for session {}", session.sessionId);
                }
                
                // Queue any pending audio data
                while (queuedBuffers < BUFFER_COUNT - 1 && !session.audioQueue.isEmpty()) {
                    short[] data = session.audioQueue.poll();
                    if (data != null) {
                        queueBuffer(session, data);
                        queuedBuffers++;
                    }
                }
                
                // Update position if 3D
                if (session.position != null) {
                    AL10.alSource3f(session.source, AL10.AL_POSITION,
                            (float) session.position.x, 
                            (float) session.position.y, 
                            (float) session.position.z);
                }
                
                // Update listener position from player
                updateListenerPosition();
                
                // Small sleep to prevent busy-waiting
                Thread.sleep(10);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.error("Error in playback loop for session {}", session.sessionId, e);
        } finally {
            cleanup(session);
        }
    }
    
    /**
     * Queues a single buffer of audio data.
     */
    private static void queueBuffer(StreamingSession session, short[] pcmData) {
        int bufferIndex = session.bufferIndex.getAndUpdate(i -> (i + 1) % BUFFER_COUNT);
        int buffer = session.buffers[bufferIndex];
        
        // Upload data to buffer (mono 16-bit)
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcmData, SAMPLE_RATE);
        checkALError("alBufferData");
        
        // Queue the buffer
        AL10.alSourceQueueBuffers(session.source, buffer);
        checkALError("alSourceQueueBuffers");
    }
    
    /**
     * Removes buffers that have finished playing.
     */
    private static void removeProcessedBuffers(StreamingSession session) {
        int processed = AL10.alGetSourcei(session.source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            AL10.alSourceUnqueueBuffers(session.source);
        }
    }
    
    /**
     * Updates the OpenAL listener position from the player.
     */
    private static void updateListenerPosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            Vec3d pos = client.player.getPos();
            AL10.alListener3f(AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
            
            // Update listener orientation
            float yaw = (float) Math.toRadians(client.player.getYaw());
            float pitch = (float) Math.toRadians(client.player.getPitch());
            
            float lookX = (float) (-Math.sin(yaw) * Math.cos(pitch));
            float lookY = (float) (-Math.sin(pitch));
            float lookZ = (float) (Math.cos(yaw) * Math.cos(pitch));
            
            AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{
                    lookX, lookY, lookZ,  // "at" vector
                    0f, 1f, 0f            // "up" vector
            });
        }
    }
    
    /**
     * Cleans up OpenAL resources for a session.
     */
    private static void cleanup(StreamingSession session) {
        sessions.remove(session.sessionId);
        
        try {
            // Stop source
            int state = AL10.alGetSourcei(session.source, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_PLAYING) {
                AL10.alSourceStop(session.source);
            }
            
            // Unqueue all buffers
            int queued = AL10.alGetSourcei(session.source, AL10.AL_BUFFERS_QUEUED);
            for (int i = 0; i < queued; i++) {
                AL10.alSourceUnqueueBuffers(session.source);
            }
            
            // Delete resources
            AL10.alDeleteSources(session.source);
            AL10.alDeleteBuffers(session.buffers);
            
            Sapphicsaudiolib.LOGGER.debug("Cleaned up streaming session {}", session.sessionId);
            
        } catch (Exception e) {
            Sapphicsaudiolib.LOGGER.warn("Error cleaning up session {}: {}", session.sessionId, e.getMessage());
        }
    }
    
    /**
     * Stops a streaming session.
     */
    public static void stopSession(UUID sessionId) {
        StreamingSession session = sessions.get(sessionId);
        if (session != null) {
            session.stop();
        }
    }
    
    /**
     * Stops all active streaming sessions.
     */
    public static void stopAll() {
        sessions.values().forEach(StreamingSession::stop);
    }
    
    /**
     * Gets an active session by ID.
     */
    @Nullable
    public static StreamingSession getSession(UUID sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * Gets the number of active streaming sessions.
     */
    public static int getActiveSessionCount() {
        return sessions.size();
    }
    
    private static void checkALError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            Sapphicsaudiolib.LOGGER.warn("OpenAL error during {}: {}", operation, error);
        }
    }
}
