# VonixCore Architectury Multi-Platform Port Plan

## Executive Summary

This document outlines the comprehensive plan for porting VonixCore from a Forge-only mod to a multi-platform mod using Architectury API. The port will support:

- **1.20.1**: Fabric + Forge
- **1.18.2**: Fabric + Forge  
- **1.21.1**: Fabric + NeoForge

## 1. Module Structure

### 1.1 Common Module (`common/`)
Platform-agnostic code that compiles against Minecraft's common API.

```
common/src/main/java/network/vonix/vonixcore/
├── VonixCore.java                    # Main entry point
├── VonixCorePlatform.java            # Platform abstraction interface
├── config/
│   ├── ConfigManager.java            # Unified config management
│   ├── EssentialsConfig.java         # Essentials settings
│   ├── DatabaseConfig.java           # Database settings
│   └── DiscordConfig.java            # Discord settings
├── database/
│   ├── Database.java                 # HikariCP connection pooling
│   └── DatabaseType.java             # Enum for DB types
├── essentials/
│   ├── teleport/
│   │   ├── TeleportManager.java      # TPA, /back, safe teleport
│   │   ├── AsyncRtpManager.java      # Async RTP with spiral search
│   │   ├── TpaRequest.java           # TPA request record
│   │   └── TeleportLocation.java     # Serializable location
│   ├── homes/
│   │   ├── HomeManager.java          # Home CRUD operations
│   │   └── Home.java                 # Home record
│   ├── warps/
│   │   ├── WarpManager.java          # Warp CRUD operations
│   │   └── Warp.java                 # Warp record
│   ├── kits/
│   │   ├── KitManager.java           # Kit management
│   │   ├── Kit.java                  # Kit definition
│   │   └── KitItem.java              # Kit item wrapper
│   └── admin/
│       └── AdminManager.java         # Heal, feed, fly, god, vanish, ban, mute
├── permissions/
│   ├── PermissionManager.java        # Group/user permissions
│   ├── PermissionGroup.java          # Group definition
│   ├── PermissionUser.java           # User permissions
│   └── PermissionCommands.java       # /perm, /group commands
├── chat/
│   └── ChatFormatter.java            # MiniMessage-style formatting
├── commands/
│   ├── VonixCoreCommands.java        # Main /vonixcore command
│   ├── UtilityCommands.java          # tp, tphere, tpall, rtp, nick, etc.
│   └── WorldCommands.java            # weather, time, lightning, afk
├── event/
│   ├── EssentialsEventHandler.java   # Chat, join, quit events
│   └── PlayerEventListener.java      # Player state tracking
└── discord/
    ├── DiscordManager.java           # Javacord + Webhooks
    ├── DiscordEventHandler.java      # MC → Discord events
    ├── DiscordConfig.java            # Discord configuration
    ├── AdvancementData.java          # Advancement data holder
    ├── AdvancementDataExtractor.java # Extract advancement info
    ├── AdvancementEmbedDetector.java # Detect advancement embeds
    ├── AdvancementType.java          # Advancement type enum
    ├── EventData.java                # Event data holder
    ├── EventDataExtractor.java       # Extract event info
    ├── EventEmbedDetector.java       # Detect event embeds
    ├── VanillaComponentBuilder.java  # Vanilla-style messages
    ├── ServerPrefixConfig.java       # Server prefix management
    ├── EmbedFactory.java             # Discord embed builder
    ├── ExtractionException.java      # Extraction error
    ├── LinkedAccountsManager.java    # Account linking
    └── PlayerPreferences.java        # Per-player Discord prefs
```

### 1.2 Platform Modules

#### Fabric (`fabric/`)
```
fabric/src/main/java/network/vonix/vonixcore/fabric/
├── VonixCoreFabric.java              # ModInitializer entry point
├── platform/
│   └── FabricPlatform.java           # Fabric-specific implementations
└── client/
    └── VonixCoreFabricClient.java    # Client-side init (if needed)
```

#### Forge (`forge/`)
```
forge/src/main/java/network/vonix/vonixcore/forge/
├── VonixCoreForge.java               # @Mod entry point
└── platform/
    └── ForgePlatform.java            # Forge-specific implementations
```

#### NeoForge (`neoforge/`)
```
neoforge/src/main/java/network/vonix/vonixcore/neoforge/
├── VonixCoreNeoForge.java            # @Mod entry point
└── platform/
    └── NeoForgePlatform.java         # NeoForge-specific implementations
```

## 2. Platform Abstraction Layer

### 2.1 VonixCorePlatform Interface

```java
public interface VonixCorePlatform {
    // Lifecycle
    void onInitialize();
    void onServerStarting(MinecraftServer server);
    void onServerStopping(MinecraftServer server);
    
    // Configuration
    Path getConfigDirectory();
    <T> T registerConfig(String name, Class<T> configClass);
    
    // Events
    void registerEventHandlers();
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher);
    
    // Logging
    Logger getLogger();
    
    // Platform info
    String getPlatformName();
    boolean isFabric();
    boolean isForge();
    boolean isNeoForge();
}
```

### 2.2 Platform-Specific Implementations

Each platform implements `VonixCorePlatform` with platform-specific code:

- **Config**: Fabric uses `MidnightConfig` or custom; Forge uses `ForgeConfigSpec`; NeoForge uses `ModConfigSpec`
- **Events**: All use Architectury Events API for common events
- **Commands**: All use Brigadier through Architectury Command API

## 3. Configuration System Migration

### 3.1 Current Forge ConfigSpec (to be replaced)

```java
// OLD: Forge-specific
public class EssentialsConfig {
    public static final ForgeConfigSpec SPEC;
    public static final EssentialsConfig CONFIG;
    
    public final ForgeConfigSpec.BooleanValue enabled;
    public final ForgeConfigSpec.IntValue maxHomes;
    // ...
}
```

### 3.2 New Architectury-Agnostic Config

Use **MidnightLib** for Fabric and **Forge Config API Port** for cross-platform compatibility:

```java
// NEW: Platform-agnostic with Architectury Config
@Config(name = "vonixcore/essentials")
public class EssentialsConfig implements ConfigData {
    @ConfigEntry.Gui.Tooltip
    public boolean enabled = true;
    
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 1, max = 100)
    public int maxHomes = 5;
    
    // Auto-synced across client/server
    public static EssentialsConfig get() {
        return AutoConfig.getConfigHolder(EssentialsConfig.class).getConfig();
    }
}
```

### 3.3 Config Files Mapping

| Old File | New Location | Format |
|----------|--------------|--------|
| `vonixcore-database.toml` | `config/vonixcore/database.json` | JSON5 |
| `vonixcore-essentials.toml` | `config/vonixcore/essentials.json` | JSON5 |
| `vonixcore-discord.toml` | `config/vonixcore/discord.json` | JSON5 |

## 4. Event System Migration

### 4.1 Current Forge EventBus

```java
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class EssentialsEventHandler {
    @SubscribeEvent
    public static void onChatFormat(ServerChatEvent event) { }
    
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) { }
}
```

### 4.2 New Architectury Events

```java
public class EssentialsEventHandler {
    public static void register() {
        // Chat events
        PlayerEvent.CHAT.register((player, message) -> {
            // Handle chat formatting
            return EventResult.pass();
        });
        
        // Player join
        PlayerEvent.PLAYER_JOIN.register(player -> {
            // Handle join
        });
        
        // Player quit
        PlayerEvent.PLAYER_QUIT.register(player -> {
            // Handle quit
        });
        
        // Server lifecycle
        LifecycleEvent.SERVER_STARTING.register(server -> {
            VonixCore.getInstance().onServerStarting(server);
        });
    }
}
```

### 4.3 Event Mapping

| Forge Event | Architectury Event |
|-------------|-------------------|
| `ServerChatEvent` | `PlayerEvent.CHAT` |
| `PlayerLoggedInEvent` | `PlayerEvent.PLAYER_JOIN` |
| `PlayerLoggedOutEvent` | `PlayerEvent.PLAYER_QUIT` |
| `ServerStartingEvent` | `LifecycleEvent.SERVER_STARTING` |
| `ServerStoppingEvent` | `LifecycleEvent.SERVER_STOPPING` |
| `RegisterCommandsEvent` | `CommandRegistrationEvent` |
| `AdvancementEvent` | `PlayerEvent.PLAYER_ADVANCEMENT` |
| `PlayerDeathEvent` | `EntityEvent.LIVING_DEATH` |

## 5. Command System Migration

### 5.1 Current Forge Command Registration

```java
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class VonixCoreCommands {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        registerHomeCommands(dispatcher);
        // ...
    }
}
```

### 5.2 New Architectury Command Registration

```java
public class VonixCoreCommands {
    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            registerHomeCommands(dispatcher);
            registerWarpCommands(dispatcher);
            registerTeleportCommands(dispatcher);
            registerKitCommands(dispatcher);
            registerAdminCommands(dispatcher);
            registerUtilityCommands(dispatcher);
            registerWorldCommands(dispatcher);
            registerPermissionCommands(dispatcher);
            registerVonixCoreCommand(dispatcher);
        });
    }
}
```

### 5.3 Command Structure

All commands use Brigadier directly - no changes needed to command implementations:

```java
private static void registerHomeCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("home")
        .executes(ctx -> home(ctx, "home"))
        .then(Commands.argument("name", StringArgumentType.word())
            .executes(ctx -> home(ctx, StringArgumentType.getString(ctx, "name")))));
    
    dispatcher.register(Commands.literal("sethome")
        .then(Commands.argument("name", StringArgumentType.word())
            .executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "name"))))
        .executes(ctx -> setHome(ctx, "home")));
    
    dispatcher.register(Commands.literal("delhome")
        .then(Commands.argument("name", StringArgumentType.word())
            .executes(ctx -> delHome(ctx, StringArgumentType.getString(ctx, "name")))));
    
    dispatcher.register(Commands.literal("homes")
        .executes(VonixCoreCommands::listHomes));
}
```

## 6. Database & Dependencies

### 6.1 Shadow Plugin Configuration

All database drivers and external libraries are shadowed (bundled) into the mod:

```gradle
// common/build.gradle
dependencies {
    // Database drivers (shadowed)
    shadow(libs.sqlite.jdbc)
    shadow(libs.mysql.connector)
    shadow(libs.postgresql)
    shadow(libs.hikari.cp)
    
    // Discord (shadowed)
    shadow(libs.javacord)
    shadow(libs.okhttp)
    shadow(libs.gson)
}

// fabric/build.gradle & forge/build.gradle
shadowJar {
    configurations = [project.configurations.shadowBundle]
    archiveClassifier = 'dev-shadow'
    
    // Relocate to avoid conflicts
    relocate 'com.zaxxer.hikari', 'network.vonix.vonixcore.libs.hikari'
    relocate 'com.mysql', 'network.vonix.vonixcore.libs.mysql'
    relocate 'org.javacord', 'network.vonix.vonixcore.libs.javacord'
}
```

### 6.2 Database Dependencies per Platform

| Dependency | Version | Shadowed |
|------------|---------|----------|
| HikariCP | 5.1.0 | Yes |
| SQLite JDBC | 3.44.1.0 | Yes |
| MySQL Connector/J | 8.2.0 | Yes |
| PostgreSQL JDBC | 42.7.1 | Yes |
| Javacord | 3.8.0 | Yes |
| OkHttp | 4.12.0 | Yes (transitive) |
| Gson | 2.10.1 | Yes (transitive) |

## 7. Version-Specific Adaptations

### 7.1 1.20.1 (Fabric + Forge)

- **Minecraft**: 1.20.1
- **Java**: 17
- **Architectury API**: 9.2.14
- **Fabric API**: 0.92.7+1.20.1
- **Forge**: 47.4.10

Key adaptations:
- Uses `LevelResource` for world paths
- Modern Brigadier API
- Standard Architectury events

### 7.2 1.18.2 (Fabric + Forge)

- **Minecraft**: 1.18.2
- **Java**: 17
- **Architectury API**: 4.12.94
- **Fabric API**: 0.77.0+1.18.2
- **Forge**: 40.3.11

Key adaptations:
- Legacy `LevelStorageSource` for world paths
- Older Brigadier API (minor differences)
- Some event differences (check Architectury 4.x docs)

### 7.3 1.21.1 (Fabric + NeoForge)

- **Minecraft**: 1.21.1
- **Java**: 21
- **Architectury API**: 13.0.8
- **Fabric API**: 0.116.8+1.21.1
- **NeoForge**: 21.1.215

Key adaptations:
- Java 21 required
- NeoForge instead of Forge
- Updated package names (`net.neoforged` vs `net.minecraftforge`)
- Component API changes (Text → Component)

## 8. Build System

### 8.1 Root build.gradle

```gradle
plugins {
    id 'dev.architectury.loom' version '1.11-SNAPSHOT' apply false
    id 'architectury-plugin' version '3.4-SNAPSHOT'
    id 'com.gradleup.shadow' version '8.3.6' apply false
}

architectury {
    minecraft = project.minecraft_version
}

allprojects {
    group = rootProject.maven_group
    version = rootProject.mod_version
}

subprojects {
    apply plugin: 'dev.architectury.loom'
    apply plugin: 'architectury-plugin'
    apply plugin: 'maven-publish'
    apply plugin: 'com.gradleup.shadow'
    
    base {
        archivesName = "$rootProject.archives_name-$project.name"
    }
    
    loom {
        silentMojangMappingsLicense()
    }
    
    dependencies {
        minecraft "net.minecraft:minecraft:$rootProject.minecraft_version"
        mappings loom.officialMojangMappings()
    }
    
    java {
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_17 // or 21 for 1.21
        targetCompatibility = JavaVersion.VERSION_17 // or 21 for 1.21
    }
}
```

### 8.2 Common build.gradle

```gradle
architectury {
    common rootProject.enabled_platforms.split(',')
}

dependencies {
    modImplementation "net.fabricmc:fabric-loader:$rootProject.fabric_loader_version"
    modImplementation "dev.architectury:architectury:$rootProject.architectury_api_version"
    
    // Shadow dependencies
    shadow(libs.hikari.cp)
    shadow(libs.sqlite.jdbc)
    shadow(libs.mysql.connector)
    shadow(libs.postgresql)
    shadow(libs.javacord)
}
```

## 9. Implementation Phases

### Phase 1: Foundation (Week 1)
1. Set up common module structure
2. Create platform abstraction layer
3. Migrate configuration system
4. Set up shadow plugin for dependencies

### Phase 2: Essentials Module (Week 2)
1. Port TeleportManager and RTP
2. Port HomeManager and WarpManager
3. Port KitManager
4. Port AdminManager
5. Port PermissionManager

### Phase 3: Chat & Commands (Week 3)
1. Port ChatFormatter
2. Port all command classes
3. Set up command registration
4. Test all commands on both platforms

### Phase 4: Discord Integration (Week 4)
1. Port DiscordManager with Javacord
2. Port all Discord event handlers
3. Port advancement/event extractors
4. Test bidirectional chat

### Phase 5: Testing & Polish (Week 5)
1. Test all features on Fabric
2. Test all features on Forge/NeoForge
3. Performance testing
4. Documentation

## 10. File Mapping

### Essentials Module Files

| Source (Forge) | Destination (Common) | Notes |
|----------------|---------------------|-------|
| `teleport/TeleportManager.java` | `essentials/teleport/TeleportManager.java` | No changes |
| `teleport/AsyncRtpManager.java` | `essentials/teleport/AsyncRtpManager.java` | No changes |
| `teleport/TpaRequest.java` | `essentials/teleport/TpaRequest.java` | Record → Class for <1.17 |
| `teleport/TeleportLocation.java` | `essentials/teleport/TeleportLocation.java` | Record → Class for <1.17 |
| `homes/HomeManager.java` | `essentials/homes/HomeManager.java` | No changes |
| `homes/Home.java` | `essentials/homes/Home.java` | Record → Class for <1.17 |
| `warps/WarpManager.java` | `essentials/warps/WarpManager.java` | No changes |
| `warps/Warp.java` | `essentials/warps/Warp.java` | Record → Class for <1.17 |
| `kits/KitManager.java` | `essentials/kits/KitManager.java` | No changes |
| `kits/Kit.java` | `essentials/kits/Kit.java` | Record → Class for <1.17 |
| `kits/KitItem.java` | `essentials/kits/KitItem.java` | Record → Class for <1.17 |
| `admin/AdminManager.java` | `essentials/admin/AdminManager.java` | No changes |
| `permissions/PermissionManager.java` | `permissions/PermissionManager.java` | No changes |
| `permissions/PermissionGroup.java` | `permissions/PermissionGroup.java` | No changes |
| `permissions/PermissionUser.java` | `permissions/PermissionUser.java` | No changes |
| `permissions/PermissionCommands.java` | `permissions/PermissionCommands.java` | No changes |
| `chat/ChatFormatter.java` | `chat/ChatFormatter.java` | No changes |
| `command/UtilityCommands.java` | `commands/UtilityCommands.java` | No changes |
| `command/WorldCommands.java` | `commands/WorldCommands.java` | No changes |
| `command/VonixCoreCommands.java` | `commands/VonixCoreCommands.java` | Adapt registration |
| `listener/EssentialsEventHandler.java` | `event/EssentialsEventHandler.java` | Use Arch events |
| `listener/PlayerEventListener.java` | `event/PlayerEventListener.java` | Use Arch events |

### Discord Module Files

| Source (Forge) | Destination (Common) | Notes |
|----------------|---------------------|-------|
| `discord/DiscordManager.java` | `discord/DiscordManager.java` | No changes |
| `discord/DiscordEventHandler.java` | `discord/DiscordEventHandler.java` | Use Arch events |
| `discord/DiscordConfig.java` | `discord/DiscordConfig.java` | Adapt config |
| `discord/AdvancementData.java` | `discord/AdvancementData.java` | No changes |
| `discord/AdvancementDataExtractor.java` | `discord/AdvancementDataExtractor.java` | No changes |
| `discord/AdvancementEmbedDetector.java` | `discord/AdvancementEmbedDetector.java` | No changes |
| `discord/AdvancementType.java` | `discord/AdvancementType.java` | No changes |
| `discord/EventData.java` | `discord/EventData.java` | No changes |
| `discord/EventDataExtractor.java` | `discord/EventDataExtractor.java` | No changes |
| `discord/EventEmbedDetector.java` | `discord/EventEmbedDetector.java` | No changes |
| `discord/VanillaComponentBuilder.java` | `discord/VanillaComponentBuilder.java` | No changes |
| `discord/ServerPrefixConfig.java` | `discord/ServerPrefixConfig.java` | No changes |
| `discord/EmbedFactory.java` | `discord/EmbedFactory.java` | No changes |
| `discord/ExtractionException.java` | `discord/ExtractionException.java` | No changes |
| `discord/LinkedAccountsManager.java` | `discord/LinkedAccountsManager.java` | No changes |
| `discord/PlayerPreferences.java` | `discord/PlayerPreferences.java` | No changes |

### Config Files

| Source (Forge) | Destination (Common) | Notes |
|----------------|---------------------|-------|
| `config/DatabaseConfig.java` | `config/DatabaseConfig.java` | Use Arch Config |
| `config/EssentialsConfig.java` | `config/EssentialsConfig.java` | Use Arch Config |
| `config/DiscordConfig.java` | `config/DiscordConfig.java` | Use Arch Config |
| `database/Database.java` | `database/Database.java` | Path abstraction |

## 11. Testing Strategy

### 11.1 Unit Tests
- Database operations (mocked)
- Permission calculations
- Chat formatting
- Config serialization

### 11.2 Integration Tests
- Command registration
- Event handling
- Database connectivity (all types)
- Discord webhook delivery

### 11.3 Platform Testing Matrix

| Feature | Fabric 1.20.1 | Forge 1.20.1 | Fabric 1.18.2 | Forge 1.18.2 | Fabric 1.21.1 | NeoForge 1.21.1 |
|---------|---------------|--------------|---------------|--------------|---------------|-----------------|
| Homes | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Warps | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TPA | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| RTP | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Kits | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Admin | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Permissions | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Chat | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Discord | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## 12. Migration Checklist

### Pre-Migration
- [ ] Backup all existing code
- [ ] Document current feature set
- [ ] Identify platform-specific code
- [ ] Set up Architectury templates

### Migration
- [ ] Create common module structure
- [ ] Implement platform abstraction
- [ ] Migrate configuration system
- [ ] Port Essentials module
- [ ] Port Discord module
- [ ] Set up shadow dependencies
- [ ] Register commands via Architectury
- [ ] Register events via Architectury

### Post-Migration
- [ ] Test on all 6 platform/version combinations
- [ ] Performance benchmarks
- [ ] Update documentation
- [ ] Create migration guide for users
- [ ] Release beta versions

## 13. Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Database driver conflicts | High | Shadow/relocate all drivers |
| Discord lib version conflicts | Medium | Shadow Javacord + OkHttp |
| Event differences between versions | Medium | Version-specific event handlers |
| Config migration for users | Medium | Provide migration tool |
| Performance regression | Low | Benchmark before/after |
| Platform-specific bugs | Medium | Extensive testing matrix |

## 14. Success Criteria

1. **Feature Parity**: All Essentials and Discord features work identically across all platforms
2. **Performance**: No more than 5% performance regression vs Forge-only
3. **Compatibility**: Works with major mods on each platform
4. **Maintainability**: Single codebase for all platforms
5. **User Experience**: Seamless migration for existing users

---

*Document Version: 1.0*
*Last Updated: 2026-02-01*
*Author: Architect Mode*

## Executive Summary

This document outlines the comprehensive plan for porting VonixCore from a Forge-only mod to a multi-platform mod using Architectury API. The port will support:

- **1.20.1**: Fabric + Forge
- **1.18.2**: Fabric + Forge  
- **1.21.1**: Fabric + NeoForge

## 1. Module Structure

### 1.1 Common Module (`common/`)
Platform-agnostic code that compiles against Minecraft's common API.

```
common/src/main/java/network/vonix/vonixcore/
├── VonixCore.java                    # Main entry point
├── VonixCorePlatform.java            # Platform abstraction interface
├── config/
│   ├── ConfigManager.java            # Unified config management
│   ├── EssentialsConfig.java         # Essentials settings
│   ├── DatabaseConfig.java           # Database settings
│   └── DiscordConfig.java            # Discord settings
├── database/
│   ├── Database.java                 # HikariCP connection pooling
│   └── DatabaseType.java             # Enum for DB types
├── essentials/
│   ├── teleport/
│   │   ├── TeleportManager.java      # TPA, /back, safe teleport
│   │   ├── AsyncRtpManager.java      # Async RTP with spiral search
│   │   ├── TpaRequest.java           # TPA request record
│   │   └── TeleportLocation.java     # Serializable location
│   ├── homes/
│   │   ├── HomeManager.java          # Home CRUD operations
│   │   └── Home.java                 # Home record
│   ├── warps/
│   │   ├── WarpManager.java          # Warp CRUD operations
│   │   └── Warp.java                 # Warp record
│   ├── kits/
│   │   ├── KitManager.java           # Kit management
│   │   ├── Kit.java                  # Kit definition
│   │   └── KitItem.java              # Kit item wrapper
│   └── admin/
│       └── AdminManager.java         # Heal, feed, fly, god, vanish, ban, mute
├── permissions/
│   ├── PermissionManager.java        # Group/user permissions
│   ├── PermissionGroup.java          # Group definition
│   ├── PermissionUser.java           # User permissions
│   └── PermissionCommands.java       # /perm, /group commands
├── chat/
│   └── ChatFormatter.java            # MiniMessage-style formatting
├── commands/
│   ├── VonixCoreCommands.java        # Main /vonixcore command
│   ├── UtilityCommands.java          # tp, tphere, tpall, rtp, nick, etc.
│   └── WorldCommands.java            # weather, time, lightning, afk
├── event/
│   ├── EssentialsEventHandler.java   # Chat, join, quit events
│   └── PlayerEventListener.java      # Player state tracking
└── discord/
    ├── DiscordManager.java           # Javacord + Webhooks
    ├── DiscordEventHandler.java      # MC → Discord events
    ├── DiscordConfig.java            # Discord configuration
    ├── AdvancementData.java          # Advancement data holder
    ├── AdvancementDataExtractor.java # Extract advancement info
    ├── AdvancementEmbedDetector.java # Detect advancement embeds
    ├── AdvancementType.java          # Advancement type enum
    ├── EventData.java                # Event data holder
    ├── EventDataExtractor.java       # Extract event info
    ├── EventEmbedDetector.java       # Detect event embeds
    ├── VanillaComponentBuilder.java  # Vanilla-style messages
    ├── ServerPrefixConfig.java       # Server prefix management
    ├── EmbedFactory.java             # Discord embed builder
    ├── ExtractionException.java      # Extraction error
    ├── LinkedAccountsManager.java    # Account linking
    └── PlayerPreferences.java        # Per-player Discord prefs
```

### 1.2 Platform Modules

#### Fabric (`fabric/`)
```
fabric/src/main/java/network/vonix/vonixcore/fabric/
├── VonixCoreFabric.java              # ModInitializer entry point
├── platform/
│   └── FabricPlatform.java           # Fabric-specific implementations
└── client/
    └── VonixCoreFabricClient.java    # Client-side init (if needed)
```

#### Forge (`forge/`)
```
forge/src/main/java/network/vonix/vonixcore/forge/
├── VonixCoreForge.java               # @Mod entry point
└── platform/
    └── ForgePlatform.java            # Forge-specific implementations
```

#### NeoForge (`neoforge/`)
```
neoforge/src/main/java/network/vonix/vonixcore/neoforge/
├── VonixCoreNeoForge.java            # @Mod entry point
└── platform/
    └── NeoForgePlatform.java         # NeoForge-specific implementations
```

## 2. Platform Abstraction Layer

### 2.1 VonixCorePlatform Interface

```java
public interface VonixCorePlatform {
    // Lifecycle
    void onInitialize();
    void onServerStarting(MinecraftServer server);
    void onServerStopping(MinecraftServer server);
    
    // Configuration
    Path getConfigDirectory();
    <T> T registerConfig(String name, Class<T> configClass);
    
    // Events
    void registerEventHandlers();
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher);
    
    // Logging
    Logger getLogger();
    
    // Platform info
    String getPlatformName();
    boolean isFabric();
    boolean isForge();
    boolean isNeoForge();
}
```

### 2.2 Platform-Specific Implementations

Each platform implements `VonixCorePlatform` with platform-specific code:

- **Config**: Fabric uses `MidnightConfig` or custom; Forge uses `ForgeConfigSpec`; NeoForge uses `ModConfigSpec`
- **Events**: All use Architectury Events API for common events
- **Commands**: All use Brigadier through Architectury Command API

## 3. Configuration System Migration

### 3.1 Current Forge ConfigSpec (to be replaced)

```java
// OLD: Forge-specific
public class EssentialsConfig {
    public static final ForgeConfigSpec SPEC;
    public static final EssentialsConfig CONFIG;
    
    public final ForgeConfigSpec.BooleanValue enabled;
    public final ForgeConfigSpec.IntValue maxHomes;
    // ...
}
```

### 3.2 New Architectury-Agnostic Config

Use **MidnightLib** for Fabric and **Forge Config API Port** for cross-platform compatibility:

```java
// NEW: Platform-agnostic with Architectury Config
@Config(name = "vonixcore/essentials")
public class EssentialsConfig implements ConfigData {
    @ConfigEntry.Gui.Tooltip
    public boolean enabled = true;
    
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 1, max = 100)
    public int maxHomes = 5;
    
    // Auto-synced across client/server
    public static EssentialsConfig get() {
        return AutoConfig.getConfigHolder(EssentialsConfig.class).getConfig();
    }
}
```

### 3.3 Config Files Mapping

| Old File | New Location | Format |
|----------|--------------|--------|
| `vonixcore-database.toml` | `config/vonixcore/database.json` | JSON5 |
| `vonixcore-essentials.toml` | `config/vonixcore/essentials.json` | JSON5 |
| `vonixcore-discord.toml` | `config/vonixcore/discord.json` | JSON5 |

## 4. Event System Migration

### 4.1 Current Forge EventBus

```java
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class EssentialsEventHandler {
    @SubscribeEvent
    public static void onChatFormat(ServerChatEvent event) { }
    
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) { }
}
```

### 4.2 New Architectury Events

```java
public class EssentialsEventHandler {
    public static void register() {
        // Chat events
        PlayerEvent.CHAT.register((player, message) -> {
            // Handle chat formatting
            return EventResult.pass();
        });
        
        // Player join
        PlayerEvent.PLAYER_JOIN.register(player -> {
            // Handle join
        });
        
        // Player quit
        PlayerEvent.PLAYER_QUIT.register(player -> {
            // Handle quit
        });
        
        // Server lifecycle
        LifecycleEvent.SERVER_STARTING.register(server -> {
            VonixCore.getInstance().onServerStarting(server);
        });
    }
}
```

### 4.3 Event Mapping

| Forge Event | Architectury Event |
|-------------|-------------------|
| `ServerChatEvent` | `PlayerEvent.CHAT` |
| `PlayerLoggedInEvent` | `PlayerEvent.PLAYER_JOIN` |
| `PlayerLoggedOutEvent` | `PlayerEvent.PLAYER_QUIT` |
| `ServerStartingEvent` | `LifecycleEvent.SERVER_STARTING` |
| `ServerStoppingEvent` | `LifecycleEvent.SERVER_STOPPING` |
| `RegisterCommandsEvent` | `CommandRegistrationEvent` |
| `AdvancementEvent` | `PlayerEvent.PLAYER_ADVANCEMENT` |
| `PlayerDeathEvent` | `EntityEvent.LIVING_DEATH` |

## 5. Command System Migration

### 5.1 Current Forge Command Registration

```java
@Mod.EventBusSubscriber(modid = VonixCore.MODID)
public class VonixCoreCommands {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        registerHomeCommands(dispatcher);
        // ...
    }
}
```

### 5.2 New Architectury Command Registration

```java
public class VonixCoreCommands {
    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> {
            registerHomeCommands(dispatcher);
            registerWarpCommands(dispatcher);
            registerTeleportCommands(dispatcher);
            registerKitCommands(dispatcher);
            registerAdminCommands(dispatcher);
            registerUtilityCommands(dispatcher);
            registerWorldCommands(dispatcher);
            registerPermissionCommands(dispatcher);
            registerVonixCoreCommand(dispatcher);
        });
    }
}
```

### 5.3 Command Structure

All commands use Brigadier directly - no changes needed to command implementations:

```java
private static void registerHomeCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(Commands.literal("home")
        .executes(ctx -> home(ctx, "home"))
        .then(Commands.argument("name", StringArgumentType.word())
            .executes(ctx -> home(ctx, StringArgumentType.getString(ctx, "name")))));
    
    dispatcher.register(Commands.literal("sethome")
        .then(Commands.argument("name", StringArgumentType.word())
            .executes(ctx -> setHome(ctx, StringArgumentType.getString(ctx, "name"))))
        .executes(ctx -> setHome(ctx, "home")));
    
    dispatcher.register(Commands.literal("delhome")
        .then(Commands.argument("name", StringArgumentType.word())
            .executes(ctx -> delHome(ctx, StringArgumentType.getString(ctx, "name")))));
    
    dispatcher.register(Commands.literal("homes")
        .executes(VonixCoreCommands::listHomes));
}
```

## 6. Database & Dependencies

### 6.1 Shadow Plugin Configuration

All database drivers and external libraries are shadowed (bundled) into the mod:

```gradle
// common/build.gradle
dependencies {
    // Database drivers (shadowed)
    shadow(libs.sqlite.jdbc)
    shadow(libs.mysql.connector)
    shadow(libs.postgresql)
    shadow(libs.hikari.cp)
    
    // Discord (shadowed)
    shadow(libs.javacord)
    shadow(libs.okhttp)
    shadow(libs.gson)
}

// fabric/build.gradle & forge/build.gradle
shadowJar {
    configurations = [project.configurations.shadowBundle]
    archiveClassifier = 'dev-shadow'
    
    // Relocate to avoid conflicts
    relocate 'com.zaxxer.hikari', 'network.vonix.vonixcore.libs.hikari'
    relocate 'com.mysql', 'network.vonix.vonixcore.libs.mysql'
    relocate 'org.javacord', 'network.vonix.vonixcore.libs.javacord'
}
```

### 6.2 Database Dependencies per Platform

| Dependency | Version | Shadowed |
|------------|---------|----------|
| HikariCP | 5.1.0 | Yes |
| SQLite JDBC | 3.44.1.0 | Yes |
| MySQL Connector/J | 8.2.0 | Yes |
| PostgreSQL JDBC | 42.7.1 | Yes |
| Javacord | 3.8.0 | Yes |
| OkHttp | 4.12.0 | Yes (transitive) |
| Gson | 2.10.1 | Yes (transitive) |

## 7. Version-Specific Adaptations

### 7.1 1.20.1 (Fabric + Forge)

- **Minecraft**: 1.20.1
- **Java**: 17
- **Architectury API**: 9.2.14
- **Fabric API**: 0.92.7+1.20.1
- **Forge**: 47.4.10

Key adaptations:
- Uses `LevelResource` for world paths
- Modern Brigadier API
- Standard Architectury events

### 7.2 1.18.2 (Fabric + Forge)

- **Minecraft**: 1.18.2
- **Java**: 17
- **Architectury API**: 4.12.94
- **Fabric API**: 0.77.0+1.18.2
- **Forge**: 40.3.11

Key adaptations:
- Legacy `LevelStorageSource` for world paths
- Older Brigadier API (minor differences)
- Some event differences (check Architectury 4.x docs)

### 7.3 1.21.1 (Fabric + NeoForge)

- **Minecraft**: 1.21.1
- **Java**: 21
- **Architectury API**: 13.0.8
- **Fabric API**: 0.116.8+1.21.1
- **NeoForge**: 21.1.215

Key adaptations:
- Java 21 required
- NeoForge instead of Forge
- Updated package names (`net.neoforged` vs `net.minecraftforge`)
- Component API changes (Text → Component)

## 8. Build System

### 8.1 Root build.gradle

```gradle
plugins {
    id 'dev.architectury.loom' version '1.11-SNAPSHOT' apply false
    id 'architectury-plugin' version '3.4-SNAPSHOT'
    id 'com.gradleup.shadow' version '8.3.6' apply false
}

architectury {
    minecraft = project.minecraft_version
}

allprojects {
    group = rootProject.maven_group
    version = rootProject.mod_version
}

subprojects {
    apply plugin: 'dev.architectury.loom'
    apply plugin: 'architectury-plugin'
    apply plugin: 'maven-publish'
    apply plugin: 'com.gradleup.shadow'
    
    base {
        archivesName = "$rootProject.archives_name-$project.name"
    }
    
    loom {
        silentMojangMappingsLicense()
    }
    
    dependencies {
        minecraft "net.minecraft:minecraft:$rootProject.minecraft_version"
        mappings loom.officialMojangMappings()
    }
    
    java {
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_17 // or 21 for 1.21
        targetCompatibility = JavaVersion.VERSION_17 // or 21 for 1.21
    }
}
```

### 8.2 Common build.gradle

```gradle
architectury {
    common rootProject.enabled_platforms.split(',')
}

dependencies {
    modImplementation "net.fabricmc:fabric-loader:$rootProject.fabric_loader_version"
    modImplementation "dev.architectury:architectury:$rootProject.architectury_api_version"
    
    // Shadow dependencies
    shadow(libs.hikari.cp)
    shadow(libs.sqlite.jdbc)
    shadow(libs.mysql.connector)
    shadow(libs.postgresql)
    shadow(libs.javacord)
}
```

## 9. Implementation Phases

### Phase 1: Foundation (Week 1)
1. Set up common module structure
2. Create platform abstraction layer
3. Migrate configuration system
4. Set up shadow plugin for dependencies

### Phase 2: Essentials Module (Week 2)
1. Port TeleportManager and RTP
2. Port HomeManager and WarpManager
3. Port KitManager
4. Port AdminManager
5. Port PermissionManager

### Phase 3: Chat & Commands (Week 3)
1. Port ChatFormatter
2. Port all command classes
3. Set up command registration
4. Test all commands on both platforms

### Phase 4: Discord Integration (Week 4)
1. Port DiscordManager with Javacord
2. Port all Discord event handlers
3. Port advancement/event extractors
4. Test bidirectional chat

### Phase 5: Testing & Polish (Week 5)
1. Test all features on Fabric
2. Test all features on Forge/NeoForge
3. Performance testing
4. Documentation

## 10. File Mapping

### Essentials Module Files

| Source (Forge) | Destination (Common) | Notes |
|----------------|---------------------|-------|
| `teleport/TeleportManager.java` | `essentials/teleport/TeleportManager.java` | No changes |
| `teleport/AsyncRtpManager.java` | `essentials/teleport/AsyncRtpManager.java` | No changes |
| `teleport/TpaRequest.java` | `essentials/teleport/TpaRequest.java` | Record → Class for <1.17 |
| `teleport/TeleportLocation.java` | `essentials/teleport/TeleportLocation.java` | Record → Class for <1.17 |
| `homes/HomeManager.java` | `essentials/homes/HomeManager.java` | No changes |
| `homes/Home.java` | `essentials/homes/Home.java` | Record → Class for <1.17 |
| `warps/WarpManager.java` | `essentials/warps/WarpManager.java` | No changes |
| `warps/Warp.java` | `essentials/warps/Warp.java` | Record → Class for <1.17 |
| `kits/KitManager.java` | `essentials/kits/KitManager.java` | No changes |
| `kits/Kit.java` | `essentials/kits/Kit.java` | Record → Class for <1.17 |
| `kits/KitItem.java` | `essentials/kits/KitItem.java` | Record → Class for <1.17 |
| `admin/AdminManager.java` | `essentials/admin/AdminManager.java` | No changes |
| `permissions/PermissionManager.java` | `permissions/PermissionManager.java` | No changes |
| `permissions/PermissionGroup.java` | `permissions/PermissionGroup.java` | No changes |
| `permissions/PermissionUser.java` | `permissions/PermissionUser.java` | No changes |
| `permissions/PermissionCommands.java` | `permissions/PermissionCommands.java` | No changes |
| `chat/ChatFormatter.java` | `chat/ChatFormatter.java` | No changes |
| `command/UtilityCommands.java` | `commands/UtilityCommands.java` | No changes |
| `command/WorldCommands.java` | `commands/WorldCommands.java` | No changes |
| `command/VonixCoreCommands.java` | `commands/VonixCoreCommands.java` | Adapt registration |
| `listener/EssentialsEventHandler.java` | `event/EssentialsEventHandler.java` | Use Arch events |
| `listener/PlayerEventListener.java` | `event/PlayerEventListener.java` | Use Arch events |

### Discord Module Files

| Source (Forge) | Destination (Common) | Notes |
|----------------|---------------------|-------|
| `discord/DiscordManager.java` | `discord/DiscordManager.java` | No changes |
| `discord/DiscordEventHandler.java` | `discord/DiscordEventHandler.java` | Use Arch events |
| `discord/DiscordConfig.java` | `discord/DiscordConfig.java` | Adapt config |
| `discord/AdvancementData.java` | `discord/AdvancementData.java` | No changes |
| `discord/AdvancementDataExtractor.java` | `discord/AdvancementDataExtractor.java` | No changes |
| `discord/AdvancementEmbedDetector.java` | `discord/AdvancementEmbedDetector.java` | No changes |
| `discord/AdvancementType.java` | `discord/AdvancementType.java` | No changes |
| `discord/EventData.java` | `discord/EventData.java` | No changes |
| `discord/EventDataExtractor.java` | `discord/EventDataExtractor.java` | No changes |
| `discord/EventEmbedDetector.java` | `discord/EventEmbedDetector.java` | No changes |
| `discord/VanillaComponentBuilder.java` | `discord/VanillaComponentBuilder.java` | No changes |
| `discord/ServerPrefixConfig.java` | `discord/ServerPrefixConfig.java` | No changes |
| `discord/EmbedFactory.java` | `discord/EmbedFactory.java` | No changes |
| `discord/ExtractionException.java` | `discord/ExtractionException.java` | No changes |
| `discord/LinkedAccountsManager.java` | `discord/LinkedAccountsManager.java` | No changes |
| `discord/PlayerPreferences.java` | `discord/PlayerPreferences.java` | No changes |

### Config Files

| Source (Forge) | Destination (Common) | Notes |
|----------------|---------------------|-------|
| `config/DatabaseConfig.java` | `config/DatabaseConfig.java` | Use Arch Config |
| `config/EssentialsConfig.java` | `config/EssentialsConfig.java` | Use Arch Config |
| `config/DiscordConfig.java` | `config/DiscordConfig.java` | Use Arch Config |
| `database/Database.java` | `database/Database.java` | Path abstraction |

## 11. Testing Strategy

### 11.1 Unit Tests
- Database operations (mocked)
- Permission calculations
- Chat formatting
- Config serialization

### 11.2 Integration Tests
- Command registration
- Event handling
- Database connectivity (all types)
- Discord webhook delivery

### 11.3 Platform Testing Matrix

| Feature | Fabric 1.20.1 | Forge 1.20.1 | Fabric 1.18.2 | Forge 1.18.2 | Fabric 1.21.1 | NeoForge 1.21.1 |
|---------|---------------|--------------|---------------|--------------|---------------|-----------------|
| Homes | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Warps | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| TPA | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| RTP | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Kits | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Admin | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Permissions | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Chat | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Discord | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |

## 12. Migration Checklist

### Pre-Migration
- [ ] Backup all existing code
- [ ] Document current feature set
- [ ] Identify platform-specific code
- [ ] Set up Architectury templates

### Migration
- [ ] Create common module structure
- [ ] Implement platform abstraction
- [ ] Migrate configuration system
- [ ] Port Essentials module
- [ ] Port Discord module
- [ ] Set up shadow dependencies
- [ ] Register commands via Architectury
- [ ] Register events via Architectury

### Post-Migration
- [ ] Test on all 6 platform/version combinations
- [ ] Performance benchmarks
- [ ] Update documentation
- [ ] Create migration guide for users
- [ ] Release beta versions

## 13. Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Database driver conflicts | High | Shadow/relocate all drivers |
| Discord lib version conflicts | Medium | Shadow Javacord + OkHttp |
| Event differences between versions | Medium | Version-specific event handlers |
| Config migration for users | Medium | Provide migration tool |
| Performance regression | Low | Benchmark before/after |
| Platform-specific bugs | Medium | Extensive testing matrix |

## 14. Success Criteria

1. **Feature Parity**: All Essentials and Discord features work identically across all platforms
2. **Performance**: No more than 5% performance regression vs Forge-only
3. **Compatibility**: Works with major mods on each platform
4. **Maintainability**: Single codebase for all platforms
5. **User Experience**: Seamless migration for existing users

---

*Document Version: 1.0*
*Last Updated: 2026-02-01*
*Author: Architect Mode*

