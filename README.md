# Daily Ender Chest Claims

Fabric server-side mod for Minecraft 1.21.11.

Players can run:

```text
/claim ec
```

Each player can claim up to 2 ender chests per real-life day. The quota resets when the server machine's calendar date changes.

Also included: one-player sleep. If any player sleeps in the overworld at night, the server advances to the next morning without requiring everyone else to sleep.

## Build

```powershell
gradle build
```

The built mod jar is created in `build/libs`.
