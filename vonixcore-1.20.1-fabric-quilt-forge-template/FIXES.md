# VonixCore Server Hang Fixes (v1.1.8)

This document summarizes the fixes applied in version 1.1.8 to address the ServerHangWatchdog crashes detected in VonixCore.

## Root Causes Identified

The crash log showed a `ServerHangWatchdog` error where a single server tick took 60+ seconds. The following issues were identified:

1. **Blocking Discord shutdown** - `sendShutdownEmbed().get(5, TimeUnit.SECONDS)` was called on the main thread
2. **No timeout protection on CompletableFuture API calls** - API calls could hang indefinitely
3. **Database initialization blocking** - Database connection could hang during server startup
4. **HTTP connection timeouts too long** - Default 5 second timeout could be exceeded by network delays
5. **LuckPerms `.join()` blocking** - No timeout on LuckPerms user loading

## Changes Made

### 1. DiscordManager.java
- Changed shutdown to use non-blocking async approach with `orTimeout()`
- Added `continueShutdown()` method to handle cleanup after async operation completes
- Prevents blocking the main server thread during Discord shutdown

### 2. VonixNetworkAPI.java
- Added hard timeout caps (3s connect, 5s read) regardless of config settings
- Added `orTimeout(10, TimeUnit.SECONDS)` to all API CompletableFuture calls
- Added `.exceptionally()` handlers to log timeout errors gracefully
- Prevents API calls from hanging indefinitely

### 3. Database.java
- Added timeout capping: max 5s for SQLite, 8s for remote databases
- Added `setInitializationFailTimeout(1)` to fail fast on connection errors
- Prevents database connection from hanging server startup

### 4. VonixCore.java
- Database initialization now uses async CompletableFuture with 15-second timeout
- Discord initialization now uses async CompletableFuture with 10-second timeout
- Server continues without database features if initialization times out
- Essentials module checks for null database before attempting to use it

### 5. XPSyncManager.java
- Final sync before shutdown now runs asynchronously with 10-second timeout
- Prevents server shutdown from hanging on XP sync API calls

### 6. AuthenticationManager.java
- Shutdown now cancels all pending timeout tasks first
- Reduced shutdown timeout from 5s to 3s to fail faster
- Prevents auth scheduler from blocking server shutdown

### 7. LuckPermsIntegration.java
- Changed `.join()` to `.get(5, TimeUnit.SECONDS)` with timeout handling
- Prevents LuckPerms user loading from blocking rank sync

## Recommendations

1. **Keep configs updated**: Ensure database and API timeout settings are reasonable (3-5 seconds max)

2. **Monitor logs**: Watch for timeout warnings in logs which indicate network issues:
   - `[VonixNetworkAPI] ... request timed out`
   - `[XPSync] ... timed out`
   - `[Discord] ... timed out`

3. **Network reliability**: If timeouts persist, check:
   - Database server connectivity
   - API endpoint availability  
   - Discord webhook/bot token validity
   - Firewall rules blocking connections

4. **Server watchdog settings**: Consider adjusting `max-tick-time` in server.properties:
   ```
   max-tick-time=60000  # 60 seconds (default)
   ```
   If issues persist, this can be increased temporarily, but the root cause should be fixed instead.

## Testing

After applying these fixes:
1. Test server startup with database offline - should timeout and continue
2. Test server shutdown - should complete within 10 seconds
3. Test player login with API offline - should timeout gracefully
4. Test Discord integration with invalid token - should timeout and continue
