# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


## [1.1.5] (1.20.1) & [1.1.1] (1.21.1) - 2026-02-04

### Fixed
- **Discord Integration**:
    - **Chat Relay**: Decoupled Discord message sending from chat formatting config. Chat messages now send to Discord even if in-game chat formatting is disabled (Fixes issues on Forge/Fabric).
    - **!list Command**:
        - Restored the use of Rich Embeds for the `!list` command instead of plain text.
        - Reverted visual style to strictly match legacy versions (Bullet points `•` and Footer separator `·`).

### Optimized
- **RTP (Random Teleport)**:
    - **Search Height**: Optimization to start scanning for safe ground from `Y=200` (up from 100) to better handle amplified terrain and avoid cave spawns.
    - **Search Persistence**: Increased maximum search attempts to 1000 to reduce failure rates in difficult terrain.

## [1.1.0] - 2026-02-02 - a70b26b

### Added
- **Discord Integration**:
    - **Embed Parsing**: Ported comprehensive embed parsing logic (detectors, extractors) to convert Discord embeds (Join, Leave, Death, Advancement) into vanilla-style Minecraft chat components.
    - **!list Command**: Implemented `!list` command in Discord to display a formatted embed of online players.
    - **Advancement Sync**: Created `PlayerAdvancementsMixin` to hook into advancement awards and send them to Discord (respecting config).
    - **Markdown Support**: Added support for parsing Discord markdown links into clickable Minecraft text components.

### Changed
- **Cross-Server Messaging**:
    - Webhooks now display with format `[ServerPrefix] Username: message` instead of showing `[Discord]`.
    - Removed `[Discord]` prefix from cross-server messages to reduce clutter.
- **Discord Links**: Regular Discord user messages now explicitly have a clickable `[Discord]` prefix.

### Fixed
- **API Compatibility**:
    - Fixed `BroadcastSystemMessage` vs `BroadcastMessage` API differences in 1.18.2.
    - Fixed `Advancement` vs `AdvancementHolder` API differences in 1.21.1.
    - Fixed `TextColor` parsing differences in 1.21.1.
