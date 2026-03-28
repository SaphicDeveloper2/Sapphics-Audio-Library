package com.sapphic.sal.client.audio;

import com.sapphic.sal.Sapphicsaudiolib;
import com.sapphic.sal.client.network.AudioReceiver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.util.UUID;

/**
 * Memory custodian responsible for cleaning up finished OpenAL resources.
 * Runs every tick to check for completed audio sessions and free their memory.
 * This prevents memory leaks by ensuring OpenAL buffers and sources are properly released.
 */
public class MemoryCustodian {
    
    // Tick counter for periodic operations
    private static int tickCounter = 0;
    
    // Statistics
    private static long totalSessionsCleaned = 0;
    private static long totalMemoryFreed = 0;
    
    /**
     * Initializes the memory custodian.
     * Registers tick handlers for automatic cleanup.
     */
    public static void initialize() {
        // Register client tick event for cleanup
        ClientTickEvents.END_CLIENT_TICK.register(MemoryCustodian::onClientTick);
        
        // Set up session listener for tracking
        AudioEngine.setSessionListener(new AudioEngine.AudioSessionListener() {
            @Override
            public void onSessionStarted(UUID sessionId, float durationSeconds) {
                // Could track memory allocation here if needed
            }
            
            @Override
            public void onSessionStopped(UUID sessionId) {
                totalSessionsCleaned++;
            }
        });
        
        Sapphicsaudiolib.LOGGER.info("SapphicsAudioLib Memory Custodian initialized");
    }
    
    /**
     * Called every client tick.
     */
    private static void onClientTick(MinecraftClient client) {
        if (client.world == null) return;
        
        tickCounter++;
        
        // Every tick: Update entity positions for tracked sounds
        AudioEngine.updateEntityPositions();
        
        // Every tick: Clean up finished sessions
        cleanupFinishedSessions();
        
        // Every 20 ticks (1 second): Cleanup expired pending sessions
        if (tickCounter % 20 == 0) {
            AudioReceiver.cleanupExpiredSessions();
        }
        
        // Every 100 ticks (5 seconds): Log statistics if debug enabled
        if (tickCounter % 100 == 0 && Sapphicsaudiolib.LOGGER.isDebugEnabled()) {
            logStatistics();
        }
    }
    
    /**
     * Checks all active sessions and cleans up any that have finished playing.
     */
    private static void cleanupFinishedSessions() {
        UUID[] sessionIds = AudioEngine.getActiveSessionIds();
        
        for (UUID sessionId : sessionIds) {
            AudioEngine.AudioSession session = AudioEngine.getSession(sessionId);
            if (session != null && session.isFinished()) {
                AudioEngine.stop(sessionId);
                Sapphicsaudiolib.LOGGER.debug("Cleaned up finished session: {}", sessionId);
            }
        }
    }
    
    /**
     * Forces immediate cleanup of all audio resources.
     * Should be called during mod unload or world disconnect.
     */
    public static void forceCleanup() {
        // Stop all active sessions
        AudioEngine.stopAll();
        
        // Clear pending sessions
        AudioReceiver.clearAll();
        
        Sapphicsaudiolib.LOGGER.info("Forced cleanup complete");
    }
    
    /**
     * Logs current statistics for debugging.
     */
    private static void logStatistics() {
        int activeSessions = AudioEngine.getActiveSessionCount();
        int pendingSessions = AudioReceiver.getPendingSessionCount();
        
        if (activeSessions > 0 || pendingSessions > 0) {
            Sapphicsaudiolib.LOGGER.debug("Audio Stats - Active: {}, Pending: {}, Total Cleaned: {}",
                    activeSessions, pendingSessions, totalSessionsCleaned);
        }
    }
    
    /**
     * Gets the total number of sessions that have been cleaned up.
     */
    public static long getTotalSessionsCleaned() {
        return totalSessionsCleaned;
    }
    
    /**
     * Gets the current number of active audio sessions.
     */
    public static int getActiveSessionCount() {
        return AudioEngine.getActiveSessionCount();
    }
    
    /**
     * Gets the current number of pending (incomplete) sessions.
     */
    public static int getPendingSessionCount() {
        return AudioReceiver.getPendingSessionCount();
    }
    
    /**
     * Resets statistics counters.
     */
    public static void resetStatistics() {
        totalSessionsCleaned = 0;
        totalMemoryFreed = 0;
    }
}
