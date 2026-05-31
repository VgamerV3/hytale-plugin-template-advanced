# hytale-plugin-template-advanced

A production-minded plugin starter that demonstrates rate limiting, queued jobs, circuit-breaker style recovery, and cached license validation patterns.

## Highlights
- heartbeat-driven maintenance and queue processing
- guard rails for quotas, retries, and provider failures
- snapshot persistence that can be extended into real storage

## Requirements
- Java 25
- Hytale Server 0.5.3
- the included Gradle wrapper

## Build
```bash
./gradlew clean build
```

Built jars are written to `build/libs/hytale-plugin-template-advanced-1.1.0.jar`, with matching sources and javadoc jars next to it.

## Commands
- `/hdadvanceddemo`: Runs an advanced demo action.
- `/hdadvancedflush`: Flushes advanced runtime snapshot.
- `/hdadvancedstatus`: Shows status for the advanced template plugin.

## Project Layout
- `src/main/java`: plugin entry point, commands, state objects, and service logic
- `src/main/resources/manifest.json`: metadata, entry class, and server target

## Install
1. Build the project with `./gradlew clean build`.
2. Copy `build/libs/hytale-plugin-template-advanced-1.1.0.jar` into your server `plugins/` directory.
3. Restart the server and run the included commands to confirm the template loaded correctly.

## What to Change First
- rename the package, command names, and manifest identifiers to match your project
- replace the demo actions with your real gameplay, economy, networking, or UI logic
- move any persistent state into the storage or config format you actually want to support

## Notes
- The Gradle build auto-detects a local `HytaleServer.jar` when one is nearby, but it can also resolve `com.hypixel.hytale:Server:0.5.3` directly from the Hytale Maven.
- The templates are intentionally small enough to read in one sitting, so you can copy them into a new repo and start renaming immediately.
