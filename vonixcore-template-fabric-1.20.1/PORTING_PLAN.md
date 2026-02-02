# VonixCore NeoForge → Fabric 1.20.1 Porting Plan

## Overview
This document tracks the progress of porting VonixCore from NeoForge to Fabric 1.20.1.

## Key Differences in Fabric

### Mod Initialization
- NeoForge: `@Mod` annotation, constructor injection
- Fabric: `ModInitializer.onInitialize()`, `DedicatedServerModInitializer.onInitializeServer()`

### Event System
- NeoForge: `@SubscribeEvent`, `IEventBus`
- Fabric: Callbacks like `ServerLifecycleEvents`, `CommandRegistrationCallback`, `PlayerEvent`

### Config System
- NeoForge: TOML-based `ModConfigSpec`
- Fabric: Custom YAML/JSON configs (using Gson or Jackson)

### Commands
- NeoForge: `RegisterCommandsEvent`
- Fabric: `CommandRegistrationCallback.EVENT`

## Modules to Port

### Phase 1: Core Infrastructure
- [x] Main mod class (VonixCore.java)
- [x] Async executor pool
- [ ] Config loading system (custom YAML loader)
- [ ] Database connection (HikariCP)

### Phase 2: Configuration System
- [ ] DatabaseConfig (YAML)
- [ ] EssentialsConfig (YAML)
- [ ] DiscordConfig (YAML)
- [ ] ProtectionConfig (YAML)
- [ ] XPSyncConfig (YAML)
- [ ] AuthConfig (YAML)
- [ ] ClaimsConfig (YAML)
- [ ] ShopsConfig (YAML)

### Phase 3: Database Layer
- [ ] Database.java (HikariCP, multi-database support)
- [ ] Table creation/migration

### Phase 4: Managers
- [ ] HomeManager
- [ ] WarpManager
- [ ] EconomyManager
- [ ] ShopManager
- [ ] KitManager
- [ ] JobsManager
- [ ] TeleportManager
- [ ] ClaimsManager
- [ ] AdminManager
- [ ] PermissionManager
- [ ] AuthenticationManager
- [ ] DiscordManager
- [ ] XPSyncManager
- [ ] Consumer (Protection data batching)

### Phase 5: Commands
- [ ] VonixCoreCommands (core/reload/status)
- [ ] Home commands (sethome, home, delhome, homes)
- [ ] Warp commands (setwarp, warp, delwarp, warps)
- [ ] TPA commands (tpa, tpahere, tpaccept, tpdeny, back)
- [ ] Economy commands (balance, pay, baltop)
- [ ] Kit commands
- [ ] Shop commands
- [ ] Protection commands (lookup, rollback)
- [ ] Utility commands (msg, reply, spawn, etc.)
- [ ] World commands
- [ ] Claims commands
- [ ] Jobs commands

### Phase 6: Event Listeners
- [ ] BlockEventListener
- [ ] EntityEventListener
- [ ] PlayerEventListener
- [ ] EssentialsEventHandler
- [ ] ProtectionEventHandler
- [ ] ExtendedProtectionListener

### Phase 7: Additional Systems
- [ ] Auth system (freeze, verification)
- [ ] Discord integration (Javacord)
- [ ] XPSync API integration

## Dependencies to Add (build.gradle)

```gradle
// Database
implementation 'com.zaxxer:HikariCP:5.1.0'
implementation 'org.xerial:sqlite-jdbc:3.45.1.0'

// Discord
implementation('org.javacord:javacord:3.8.0') {
    exclude group: 'org.apache.logging.log4j'
}

// HTTP client
implementation 'com.squareup.okhttp3:okhttp:4.12.0'

// JSON (for config)
implementation 'com.google.code.gson:gson:2.10.1'
```

## Notes
- Fabric uses Mojang mappings (same as NeoForge 1.21+)
- Most game code will transfer directly
- Main changes are in mod initialization and event registration
