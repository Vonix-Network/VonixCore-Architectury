# VonixCore (Architectury Rewrite)

This is the Architectury rewrite of VonixCore, supporting Fabric, Forge, and Quilt/NeoForge across multiple Minecraft versions (1.18.2, 1.20.1, 1.21.1).

## Modules
- **Essentials**: Homes, Warps, Kits, Teleportation (RTP), Admin Tools
- **Discord**: Bidirectional Chat, Account Linking, Cross-server Event Logging, Chat Filtering, Echo Prevention
- **XPSync**: Cross-server XP synchronization
- **Auth**: Authentication system

## Structure
- `vonixcore-1.18.2-fabric-quilt-forge-template`: 1.18.2 implementation
- `vonixcore-1.20.1-fabric-quilt-forge-template`: 1.20.1 implementation
- `vonixcore-1.21.1-fabric-neoforge-template`: 1.21.1 implementation

## Building
Run `./gradlew build` in the respective version directory.
