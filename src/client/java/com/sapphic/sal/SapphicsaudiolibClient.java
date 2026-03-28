package com.sapphic.sal;

import com.sapphic.sal.client.audio.MemoryCustodian;
import com.sapphic.sal.client.network.AudioReceiver;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Client-side initialization for SapphicsAudioLib.
 * Sets up the audio engine, network receivers, and memory management.
 */
public class SapphicsaudiolibClient implements ClientModInitializer {
	
	@Override
	public void onInitializeClient() {
		Sapphicsaudiolib.LOGGER.info("Initializing {} client components", Sapphicsaudiolib.MOD_NAME);
		
		// Register client-side network receivers
		AudioReceiver.register();
		
		// Initialize memory custodian for automatic cleanup
		MemoryCustodian.initialize();
		
		// Register cleanup on client stop
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Sapphicsaudiolib.LOGGER.info("Cleaning up {} resources", Sapphicsaudiolib.MOD_NAME);
			MemoryCustodian.forceCleanup();
		});
		
		Sapphicsaudiolib.LOGGER.info("{} client initialized successfully!", Sapphicsaudiolib.MOD_NAME);
	}
}