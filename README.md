# SapphicsAudioLib

A powerful client-side audio streaming library for Minecraft Fabric 1.21.1. Enables mods to stream custom `.ogg` audio files to nearby players with full 3D spatial positioning, real-time playback controls, and automatic memory management.

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
  - [Sound Registration](#sound-registration)
  - [Playback Methods](#playback-methods)
  - [Broadcasting Audio](#broadcasting-audio-share-any-ogg-to-others)
  - [Custom Player Sounds](#custom-player-sounds-no-serverclient-file-sync-needed)
  - [AudioSession Controls](#audiosession-controls)
  - [SoundOptions](#soundoptions)
  - [Local Playback](#local-playback)
  - [Engine Status](#engine-status)
  - [Events](#events)
- [Architecture](#architecture)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## Features

- **Custom Audio Streaming**: Stream any `.ogg` Vorbis audio file to nearby players
- **Player-to-Player Broadcasting**: Let players share any `.ogg` file with nearby players (no pre-registration needed)
- **Custom Player Sounds**: Players can set personalized sounds that others hear - no file sync needed on server or other clients
- **3D Spatial Audio**: Full OpenAL-based 3D positioning with distance attenuation
- **Entity Tracking**: Sounds can follow entities as they move
- **Real-time Controls**: Adjust volume, pitch, pause/resume during playback
- **Kill Switch**: Instantly stop any sound mid-playback
- **Automatic Memory Management**: OpenAL resources are automatically cleaned up
- **Event System**: Hook into audio lifecycle events
- **Security Registry**: Validate sound events to prevent abuse
- **Network Efficient**: Chunked streaming with ~15KB packets

---

## Installation

### For Mod Developers

Add SapphicsAudioLib as a dependency in your `build.gradle`:

```gradle
repositories {
    maven { url 'https://maven.sapphic.dev/' }
}

dependencies {
    modImplementation "com.sapphic:sapphicsaudiolib:1.0.0"
}
```

Add to your `fabric.mod.json`:

```json
{
  "depends": {
    "sapphicsaudiolib": ">=1.0.0"
  }
}
```

---

## Quick Start

### Step 1: Register Your Sound Events

During mod initialization, register all sound event identifiers your mod will use:

```java
import com.sapphic.sal.api.SapphicsAudioAPI;

public class MyMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // Register sound events (required for security validation)
        SapphicsAudioAPI.registerSound("mymod:tardis_dematerialization");
        SapphicsAudioAPI.registerSound("mymod:sonic_screwdriver");
        SapphicsAudioAPI.registerSound("mymod:console_hum");
    }
}
```

### Step 2: Play Audio (Client-Side)

On the client, use `SapphicsAudioClientAPI` to start playback:

```java
import com.sapphic.sal.client.api.SapphicsAudioClientAPI;
import com.sapphic.sal.client.api.SapphicsAudioClientAPI.SoundOptions;
import com.sapphic.sal.client.api.SapphicsAudioClientAPI.AudioSession;

// Play a sound attached to an entity
Path audioFile = Path.of("config/mymod/sounds/demat.ogg");

CompletableFuture<AudioSession> session = SapphicsAudioClientAPI.playFromEntity(
    audioFile,
    tardisEntity,
    "mymod:tardis_dematerialization",
    SoundOptions.builder()
        .volume(0.8f)
        .pitch(1.0f)
        .maxDistance(128.0f)
        .build()
);

// Handle the session when ready
session.thenAccept(s -> {
    System.out.println("Sound started with session: " + s.getSessionId());
});
```

### Step 3: Control Playback

```java
session.thenAccept(s -> {
    // Adjust volume mid-playback
    s.setVolume(0.5f);
    
    // Speed up the sound
    s.setPitch(1.5f);
    
    // Pause playback
    s.pause();
    
    // Resume playback
    s.resume();
    
    // Stop immediately (kill switch)
    s.stop();
});
```

---

## API Reference

### Sound Registration

**Class:** `com.sapphic.sal.api.SapphicsAudioAPI`

This class is available on both server and client for registering sound events.

#### Methods

| Method | Description |
|--------|-------------|
| `registerSound(String soundEventId)` | Registers a sound event identifier. Returns `true` if newly registered. |
| `registerSounds(String... soundEventIds)` | Registers multiple sound events at once. |
| `isSoundRegistered(String soundEventId)` | Checks if a sound event is registered. |
| `getRegisteredSounds()` | Returns an unmodifiable set of all registered sounds. |
| `getRegisteredSoundCount()` | Returns the number of registered sounds. |
| `unregisterSound(String soundEventId)` | Unregisters a sound event. Use with caution. |
| `onSoundRegistered(callback)` | Adds a callback for when sounds are registered. |

#### Example

```java
// Register during mod initialization
SapphicsAudioAPI.registerSounds(
    "mymod:sound_one",
    "mymod:sound_two",
    "mymod:sound_three"
);

// Check registration
if (SapphicsAudioAPI.isSoundRegistered("mymod:sound_one")) {
    // Sound is valid
}

// Listen for registrations from other mods
SapphicsAudioAPI.onSoundRegistered(soundId -> {
    System.out.println("Sound registered: " + soundId);
});
```

---

### Playback Methods

**Class:** `com.sapphic.sal.client.api.SapphicsAudioClientAPI`

> ⚠️ **CLIENT-SIDE ONLY** - These methods must only be called on the client.

#### Entity-Attached Playback

Sounds follow the entity as it moves.

```java
// With options
CompletableFuture<AudioSession> playFromEntity(
    Path audioPath,
    Entity sourceEntity,
    String soundEventId,
    SoundOptions options
)

// With defaults
CompletableFuture<AudioSession> playFromEntity(
    Path audioPath,
    Entity sourceEntity,
    String soundEventId
)

// From memory (byte array)
CompletableFuture<AudioSession> playFromEntityMemory(
    byte[] audioData,
    Entity sourceEntity,
    String soundEventId,
    SoundOptions options
)
```

#### Position-Based Playback

Sounds play at a fixed world position.

```java
// With Vec3d
CompletableFuture<AudioSession> playAtPosition(
    Path audioPath,
    Vec3d position,
    String soundEventId,
    SoundOptions options
)

// With explicit coordinates
CompletableFuture<AudioSession> playAtPosition(
    Path audioPath,
    double x, double y, double z,
    String soundEventId,
    SoundOptions options
)

// From memory at position
CompletableFuture<AudioSession> playAtPositionMemory(
    byte[] audioData,
    Vec3d position,
    String soundEventId,
    SoundOptions options
)
```

#### Examples

```java
// Play at entity with custom options
SapphicsAudioClientAPI.playFromEntity(
    Path.of("sounds/explosion.ogg"),
    targetEntity,
    "mymod:explosion",
    SoundOptions.builder()
        .volume(1.0f)
        .pitch(0.8f)
        .maxDistance(96.0f)
        .build()
);

// Play at world position with defaults
SapphicsAudioClientAPI.playAtPosition(
    Path.of("sounds/ambient.ogg"),
    new Vec3d(100, 64, 200),
    "mymod:ambient"
);

// Play from memory
byte[] audioData = Files.readAllBytes(Path.of("sounds/effect.ogg"));
SapphicsAudioClientAPI.playFromEntityMemory(
    audioData,
    player,
    "mymod:effect",
    SoundOptions.defaults()
);
```

---

### Broadcasting Audio (Share Any .ogg to Others)

The broadcast methods allow players to share **any** `.ogg` file they have with nearby players, without requiring sound event pre-registration. This is perfect for:
- Music sharing systems
- Custom voice lines
- Player-created content
- DJ/jukebox mods

#### Broadcast Methods

```java
// Broadcast from the player (follows them as they move)
CompletableFuture<AudioSession> broadcastFromEntity(
    Path audioPath,
    Entity sourceEntity,
    SoundOptions options
)

// Broadcast from a fixed position
CompletableFuture<AudioSession> broadcastAtPosition(
    Path audioPath,
    Vec3d position,
    SoundOptions options
)

// Broadcast from memory
CompletableFuture<AudioSession> broadcastFromEntityMemory(
    byte[] audioData,
    Entity sourceEntity,
    SoundOptions options
)
```

#### Example: Player Shares Music with Nearby Players

```java
// Get the audio file the player wants to share
Path musicFile = Path.of("config/mymod/music/song.ogg");

// Broadcast it to all nearby players (attached to the player)
CompletableFuture<AudioSession> session = SapphicsAudioClientAPI.broadcastFromEntity(
    musicFile,
    player,  // The sound follows this player
    SoundOptions.builder()
        .volume(0.7f)
        .maxDistance(32.0f)  // Players within 32 blocks will hear it
        .build()
);

session.thenAccept(s -> {
    System.out.println("Broadcasting music to nearby players!");
});
```

#### Example: Jukebox at a Position

```java
// Place audio at a jukebox block position
Vec3d jukeboxPos = new Vec3d(100, 64, 200);

SapphicsAudioClientAPI.broadcastAtPosition(
    Path.of("music/custom_disc.ogg"),
    jukeboxPos,
    SoundOptions.builder()
        .volume(1.0f)
        .maxDistance(64.0f)
        .build()
);
```

> **Note:** Broadcast uses the special `sapphicsaudiolib:user_audio` sound event ID internally, which is always registered and valid.

---

### Custom Player Sounds (No Server/Client File Sync Needed)

One of the most powerful features of SapphicsAudioLib is enabling **player-personalized sounds**. Players can set custom `.ogg` files for in-game actions, and **other players will hear them** - even though:
- The other players don't have the sound file
- The server doesn't need the sound file
- No resource packs are required

The audio is streamed in real-time from the player who has the file.

#### How It Works

```
┌─────────────────┐     ┌─────────────┐     ┌─────────────────┐
│   Player A      │     │   Server    │     │   Player B      │
│ (has custom.ogg)│────▶│  (relay)    │────▶│ (no file needed)│
└─────────────────┘     └─────────────┘     └─────────────────┘
        │                                            │
   Reads & sends                              Receives & plays
   audio chunks                               3D positioned audio
```

#### Example: Custom Item Use Sound

Let players set a custom sound for when they use a specific item:

```java
public class CustomSoundItem extends Item {
    
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        if (world.isClient) {
            // Check if player has a custom sound configured
            Path customSound = getPlayerCustomSound(user, "item_use");
            
            if (customSound != null && Files.exists(customSound)) {
                // Broadcast the custom sound to all nearby players
                SapphicsAudioClientAPI.broadcastFromEntity(
                    customSound,
                    user,
                    SoundOptions.builder()
                        .volume(1.0f)
                        .maxDistance(16.0f)
                        .build()
                );
            }
        }
        return super.use(world, user, hand);
    }
    
    // Load from config: config/mymod/sounds/<player_uuid>/item_use.ogg
    private Path getPlayerCustomSound(PlayerEntity player, String soundName) {
        return Path.of("config/mymod/sounds/" + player.getUuidAsString() + "/" + soundName + ".ogg");
    }
}
```

#### Example: Custom Death Sound

```java
// In a client-side event handler
ClientPlayerEvents.PLAYER_DEATH.register((player) -> {
    Path deathSound = Path.of("config/mymod/sounds/death.ogg");
    
    if (Files.exists(deathSound)) {
        SapphicsAudioClientAPI.broadcastFromEntity(
            deathSound,
            player,
            SoundOptions.builder()
                .volume(1.0f)
                .maxDistance(32.0f)
                .build()
        );
    }
});
```

#### Example: Custom Tool/Weapon Sounds

```java
public class MySwordItem extends SwordItem {
    
    @Override
    public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld().isClient && attacker instanceof PlayerEntity player) {
            // Each player can have their own custom sword sound
            Path swordSound = getPlayerSound(player, "sword_hit");
            
            if (swordSound != null) {
                // Other players hear the attacker's custom sound!
                SapphicsAudioClientAPI.broadcastFromEntity(
                    swordSound,
                    attacker,
                    SoundOptions.builder()
                        .volume(0.8f)
                        .pitch(0.9f + random.nextFloat() * 0.2f)  // Slight pitch variation
                        .maxDistance(24.0f)
                        .build()
                );
            }
        }
        return super.postHit(stack, target, attacker);
    }
}
```

#### Example: Config-Based Sound Selection UI

```java
public class SoundConfigScreen extends Screen {
    private Path selectedSound;
    
    // Let player browse and select a .ogg file
    private void onFileSelected(Path oggFile) {
        this.selectedSound = oggFile;
        
        // Preview the sound locally first
        byte[] audioData = Files.readAllBytes(oggFile);
        SapphicsAudioClientAPI.playLocal(
            audioData,
            client.player.getPos(),
            1.0f, 1.0f, 16.0f
        );
    }
    
    // Save to config when confirmed
    private void onConfirm() {
        // Copy to config folder
        Path configPath = Path.of("config/mymod/sounds/my_custom_sound.ogg");
        Files.copy(selectedSound, configPath, StandardCopyOption.REPLACE_EXISTING);
    }
}
```

#### Benefits

| Feature | Traditional Resource Packs | SapphicsAudioLib |
|---------|---------------------------|------------------|
| Other players hear custom sounds | ❌ Need same pack | ✅ Automatic |
| Server needs files | ❌ Often required | ✅ No |
| Per-player customization | ❌ Limited | ✅ Full |
| Real-time sound changes | ❌ Requires reload | ✅ Instant |
| File size limits | ❌ Pack size issues | ✅ Streams on demand |

---

### AudioSession Controls

The `AudioSession` class provides a handle for controlling active playback.

| Method | Description |
|--------|-------------|
| `getSessionId()` | Returns the unique UUID for this session |
| `stop()` | Immediately stops playback (kill switch) |
| `pause()` | Pauses playback |
| `resume()` | Resumes paused playback |
| `setVolume(float volume)` | Sets volume (0.0 to 1.0) |
| `setPitch(float pitch)` | Sets pitch multiplier (0.5 to 2.0) |
| `isPlaying()` | Returns `true` if still playing |

#### Example

```java
CompletableFuture<AudioSession> future = SapphicsAudioClientAPI.playFromEntity(...);

future.thenAccept(session -> {
    // Store session for later control
    this.currentSession = session;
});

// Later, in response to user action:
if (currentSession != null && currentSession.isPlaying()) {
    currentSession.stop();
}
```

---

### SoundOptions

Configuration record for sound playback properties.

#### Factory Methods

```java
// Default options: volume=1.0, pitch=1.0, maxDistance=64.0
SoundOptions.defaults()

// Quick volume option
SoundOptions.withVolume(0.5f)

// Full builder
SoundOptions.builder()
    .volume(0.8f)      // 0.0 to 1.0
    .pitch(1.2f)       // 0.5 to 2.0
    .maxDistance(128f) // minimum 1.0
    .build()
```

#### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `volume` | float | 1.0 | Volume level (0.0 = silent, 1.0 = full) |
| `pitch` | float | 1.0 | Pitch multiplier (0.5 = half speed, 2.0 = double speed) |
| `maxDistance` | float | 64.0 | Maximum hearing distance in blocks |

---

### Local Playback

Play audio locally without network streaming to other players. Useful for UI sounds or client-only effects.

```java
// Returns session UUID, or null if failed
UUID sessionId = SapphicsAudioClientAPI.playLocal(
    audioData,                  // byte[] - raw .ogg data
    new Vec3d(x, y, z),        // position for 3D audio
    0.8f,                       // volume
    1.0f,                       // pitch
    32.0f                       // max distance
);
```

---

### Engine Status

Methods for monitoring the audio engine state.

```java
// Check if a session is playing
boolean playing = SapphicsAudioClientAPI.isPlaying(sessionId);

// Get count of active sessions
int active = SapphicsAudioClientAPI.getActiveSessionCount();

// Get pending (incomplete) sessions - high numbers indicate network issues
int pending = SapphicsAudioClientAPI.getPendingSessionCount();

// Get total cleaned up sessions since startup
long cleaned = SapphicsAudioClientAPI.getTotalSessionsCleaned();

// Stop ALL active sessions (use with caution!)
SapphicsAudioClientAPI.stopAllSessions();

// Force cleanup of all audio resources
SapphicsAudioClientAPI.forceCleanup();
```

### Audio Analysis

Get information about audio files without playing them.

```java
byte[] oggData = Files.readAllBytes(Path.of("sound.ogg"));
AudioInfo info = SapphicsAudioClientAPI.getAudioInfo(oggData);

if (info != null) {
    System.out.println("Sample Rate: " + info.sampleRate());
    System.out.println("Channels: " + info.channels());
    System.out.println("Duration: " + info.durationSeconds() + "s");
    System.out.println("Mono: " + info.isMono());
    System.out.println("Stereo: " + info.isStereo());
}
```

---

### Events

**Class:** `com.sapphic.sal.api.SapphicsAudioEvents`

Hook into audio lifecycle events using Fabric's event system.

#### Available Events

| Event | Description |
|-------|-------------|
| `AUDIO_STARTING` | Fired before audio starts. Return `false` to cancel. |
| `AUDIO_STARTED` | Fired after audio begins playing. |
| `AUDIO_STOPPED` | Fired when audio stops (naturally or killed). |
| `CHUNK_RECEIVED` | Fired when a network chunk is received. |
| `AUDIO_REASSEMBLED` | Fired when all chunks are assembled. |

#### Examples

```java
// Cancel specific sounds
SapphicsAudioEvents.AUDIO_STARTING.register((sessionId, soundEventId, position, volume) -> {
    if (soundEventId.equals("mymod:blocked_sound")) {
        return false; // Cancel this sound
    }
    return true; // Allow
});

// Log when sounds start
SapphicsAudioEvents.AUDIO_STARTED.register((sessionId, soundEventId, position, duration) -> {
    System.out.println("Started: " + soundEventId + " (" + duration + "s)");
});

// Track when sounds stop
SapphicsAudioEvents.AUDIO_STOPPED.register((sessionId, wasKilled) -> {
    if (wasKilled) {
        System.out.println("Sound was killed: " + sessionId);
    } else {
        System.out.println("Sound finished naturally: " + sessionId);
    }
});

// Debug network chunks
SapphicsAudioEvents.CHUNK_RECEIVED.register((sessionId, chunkIndex, isLast, dataSize) -> {
    System.out.println("Chunk " + chunkIndex + " received (" + dataSize + " bytes)");
    if (isLast) {
        System.out.println("Final chunk received for session " + sessionId);
    }
});
```

---

## Architecture

SapphicsAudioLib is built with a layered architecture:

### 1. Networking Layer

Handles data transfer between clients through the server.

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client A  │────▶│   Server    │────▶│   Client B  │
│  (Sender)   │     │  (Router)   │     │  (Receiver) │
└─────────────┘     └─────────────┘     └─────────────┘
      │                   │                   │
      │  AudioChunkPayload (up to 15KB)       │
      │  - Session ID                         │
      │  - Entity ID / Position               │
      │  - Chunk Index                        │
      │  - Is Last Flag                       │
      │  - Audio Data                         │
      │  - Sound Event ID                     │
      │  - Volume, Pitch, MaxDistance         │
      └───────────────────────────────────────┘
```

**Key Classes:**
- `AudioChunkPayload` - Network packet for audio data chunks
- `AudioControlPayload` - Network packet for playback control
- `NetworkHandler` - Server-side router
- `AudioSender` - Client-side file chunker
- `AudioReceiver` - Client-side chunk reassembler

### 2. Audio Processing Layer

Decodes and plays audio through OpenAL.

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  .ogg Data   │────▶│  STB Vorbis  │────▶│   OpenAL     │
│  (in memory) │     │  (Decoder)   │     │  (Playback)  │
└──────────────┘     └──────────────┘     └──────────────┘
                           │                     │
                      PCM Audio            3D Positioned
                       Samples               Source
```

**Key Classes:**
- `AudioDecoder` - STB Vorbis wrapper for .ogg decoding
- `AudioEngine` - OpenAL source/buffer management and 3D positioning
- `MemoryCustodian` - Automatic cleanup of finished resources

### 3. API Layer

Public interfaces for mod developers.

```
┌─────────────────────────────────────────────────────────┐
│                    Your Mod Code                        │
└─────────────────────────────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
┌─────────────────┐ ┌─────────────┐ ┌─────────────────┐
│ SapphicsAudioAPI │ │ ClientAPI   │ │ AudioEvents     │
│ (Registration)  │ │ (Playback)  │ │ (Hooks)         │
└─────────────────┘ └─────────────┘ └─────────────────┘
```

---

## Best Practices

### 1. Always Register Sounds First

```java
// ✅ Good - Register during initialization
public void onInitialize() {
    SapphicsAudioAPI.registerSound("mymod:my_sound");
}

// ❌ Bad - Playing unregistered sounds will fail
SapphicsAudioClientAPI.playFromEntity(path, entity, "unregistered:sound", options);
```

### 2. Handle CompletableFuture Properly

```java
// ✅ Good - Handle both success and failure
SapphicsAudioClientAPI.playFromEntity(path, entity, soundId, options)
    .thenAccept(session -> {
        // Success
        this.activeSession = session;
    })
    .exceptionally(error -> {
        // Failure
        LOGGER.error("Failed to play sound", error);
        return null;
    });
```

### 3. Clean Up When Necessary

```java
// When leaving a world or during disconnect
SapphicsAudioClientAPI.forceCleanup();

// When a specific action is cancelled
if (activeSession != null) {
    activeSession.stop();
    activeSession = null;
}
```

### 4. Use Appropriate Max Distance

```java
// ✅ Good - Match distance to context
SoundOptions.builder()
    .maxDistance(16.0f)   // UI/close sounds
    .build();

SoundOptions.builder()
    .maxDistance(128.0f)  // Large-scale events
    .build();
```

### 5. Check Before Playing Long Sounds

```java
// For sounds that might overlap with themselves
if (activeSession == null || !activeSession.isPlaying()) {
    activeSession = SapphicsAudioClientAPI.playFromEntity(...).join();
}
```

---

## Troubleshooting

### Sound Not Playing

1. **Check Registration**: Ensure the sound event is registered before playback.
   ```java
   if (!SapphicsAudioAPI.isSoundRegistered("mymod:sound")) {
       LOGGER.warn("Sound not registered!");
   }
   ```

2. **Verify File Path**: Ensure the `.ogg` file exists and is readable.
   ```java
   Path path = Path.of("sounds/myfile.ogg");
   if (!Files.exists(path)) {
       LOGGER.error("Audio file not found: " + path);
   }
   ```

3. **Check Client-Side**: Playback methods must be called on the client.
   ```java
   if (world.isClient) {
       // Safe to call playback methods here
   }
   ```

### Sound Cuts Off Unexpectedly

- Check if another mod or your code is calling `stopAllSessions()`
- Verify the entity still exists if using entity-attached playback
- Check for exceptions in the console

### High Memory Usage

- Monitor `getActiveSessionCount()` - sounds should be cleaned up automatically
- If stuck, call `forceCleanup()` to manually release resources
- Check `getPendingSessionCount()` for network issues

### Network Issues

- High `getPendingSessionCount()` indicates chunks aren't being received
- Check server logs for relay errors
- Verify both client and server have the mod installed

---

## License

Apache License 2.0 - See [LICENSE](LICENSE) file for details.

---

## Support

- **Issues**: Report bugs on GitHub Issues
- **Discord**: Join our community server for help
- **Documentation**: Full Javadocs available in the source
