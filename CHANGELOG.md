# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.6] (1.20.1) & [1.1.2] (1.21.1) & [1.0.5] (1.18.2) - 2026-02-05

### Fixed
- **RTP (Random Teleport)**:
    - Auto-retry when location becomes unsafe during final teleport check
    - Changed `performTeleport()` to return boolean and continue searching on failure
    - Added `continueSearching()` method for up to 25 additional attempts
    - Shows "Still searching..." progress feedback every 10 attempts during extended search
    - Applied to all versions: 1.18.2, 1.20.1, and 1.21.1
- **Discord Integration** (1.21.1):
    - Fixed duplicate in-game chat messages on NeoForge (ChatEvent was double-broadcasting)
    - Added double-initialization guard in `DiscordManager.initialize()`
    - Added double-connection guard in `BotClient.connect()`
    - Improved error logging for 403 Missing Permissions errors
    - Better diagnostics when bot cannot see/send to channel
- **Discord Integration** (1.18.2):
    - Added missing error handling and logging from 1.21.1 backport
    - Added `isRunning()` checks to `sendJoinEmbed()` and `sendLeaveEmbed()`
    - Added `whenComplete()` error handling to all event embed senders
    - Added null/empty check for `eventChannelId` in `sendEventEmbedInternal()`

### Changed
- **Version Numbers** (Semantic Versioning):
    - 1.20.1: `1.1.4` → `1.1.6` (patch + new fixes)
    - 1.21.1: `1.0.4` → `1.1.2` (minor bump for Discord fixes + patch)
    - 1.18.2: `1.0.4` → `1.0.5` (patch for Discord improvements)

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
