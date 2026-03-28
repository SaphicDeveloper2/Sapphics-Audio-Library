package com.sapphic.sal;

import com.sapphic.sal.network.NetworkHandler;
import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SapphicsAudioLib - A powerful client-side audio streaming library for Minecraft.
 * 
 * This library enables mods to stream custom .ogg audio files to nearby players,
 * with full 3D spatial audio positioning and real-time controls.
 * 
 * For mod developers, see the API classes:
 * - {@link com.sapphic.sal.api.SapphicsAudioAPI} - Main API for registration and playback
 * - {@link com.sapphic.sal.api.SapphicsAudioAdvanced} - Advanced controls and utilities
 * - {@link com.sapphic.sal.api.SapphicsAudioEvents} - Event hooks for integration
 */
public class Sapphicsaudiolib implements ModInitializer {
	public static final String MOD_ID = "sapphicsaudiolib";
	public static final String MOD_NAME = "SapphicsAudioLib";
	public static final String VERSION = "1.0.0";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing {} v{}", MOD_NAME, VERSION);
		
		// Register network handlers for audio streaming
		NetworkHandler.register();
		
		LOGGER.info("{} initialized successfully!", MOD_NAME);
	}
}