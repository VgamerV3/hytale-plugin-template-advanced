# hytale-plugin-template-advanced

Advanced plugin starter with multiple production-style systems wired together:

- sender-scoped action rate limiting
- async job queue with retries
- provider circuit-breaker behavior
- license validation + binding cache
- snapshot persistence + restore
- rolling runtime audit trail

Main class: `net.hytaledepot.templates.plugin.advanced.AdvancedPluginTemplate`

## Commands

- `/hdadvancedstatus`  
  Shows lifecycle, heartbeat/maintenance state, counters, queue/breaker status, and last audit line.
- `/hdadvanceddemo <action> [args...]`  
  Runs advanced demo actions.
- `/hdadvancedflush`  
  Writes the runtime snapshot immediately.

## Demo actions

Run through these in order to see the full flow:

1. `/hdadvanceddemo queue-job`
2. `/hdadvanceddemo queue-fragile`
3. `/hdadvanceddemo process-job`
4. `/hdadvanceddemo drain-jobs`
5. `/hdadvanceddemo simulate-provider-failure`
6. `/hdadvanceddemo recover-provider`
7. `/hdadvanceddemo validate-license HD-DEMO-LICENSE 127.0.0.1`
8. `/hdadvanceddemo bind-license HD-DEMO-LICENSE 127.0.0.1`
9. `/hdadvanceddemo flush-snapshot`
10. `/hdadvanceddemo info`

If you spam demo actions too quickly, the quota guard blocks requests for a short window.

## Snapshot file

The plugin writes metrics and runtime state to:

`<plugin-data-dir>/advanced-plugin-state.properties`

It is restored during setup so counters survive restarts.

## Build

1. Ensure `HytaleServer.jar` is available (workspace root, `HYTALE_SERVER_JAR`, launcher path, or `libs/`).
2. Run:

```bash
./gradlew clean build
```

3. Copy `build/libs/hytale-plugin-template-advanced-1.0.0.jar` into `mods/`.

## License

MIT
