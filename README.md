# Telemetric

Client-side [Fabric](https://fabricmc.net/) utility mod for the **Telos Realms** Minecraft server, with a fully
animated ClickGUI, movable HUD elements and dungeon/stash tools.

## Features

| Category | Feature | What it does |
|---|---|---|
| Player | **Auto Jump** | Hold jump to jump again the instant you land (no vanilla jump delay) |
| Visual | **Stash Overlay** | Replaces the stash with all saved pages + your inventory: search, rename pages, move items across pages |
| Dungeon | **Wayfinder** | Shows the shortest way to treasure rooms and the boss drop |
| Dungeon | **Dungeon Map** | Minimap of the dungeon rooms (spawn, boss drop, treasure) and players |
| HUD | **DPS Meter** | Damage per second from the server's floating damage numbers |
| HUD | **FPS / Ping / TPS / Watermark** | Small movable info elements |
| HUD | **Tab stats** | General / character / stats info read from the Tab list |
| Misc | **Theme / Interface / HUD / Config** | Colours and presets, GUI scale and motion, HUD look, config profiles |

The ClickGUI remembers its full layout (panel positions, open modules, scroll). Settings are saved per profile.

## Controls

- **Right Shift** — open the ClickGUI (rebindable in Controls → *Telemetric*).
- Panel header: **left-drag** to move, **right-click** to collapse.
- Module row: **left-click** to toggle, **right-click** to open its settings.
- **Misc → HUD → Edit HUD** — drag to move, corner/scroll to resize, **M** to mirror, **Esc** when done.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) **0.20.1+** for **Minecraft 26.1.2**.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `telemetric-<version>.jar` into `.minecraft/mods`.
3. Requires **Java 25**.

## Building

```bash
./gradlew build
```

The jar lands in `build/libs/telemetric-<version>.jar`. Needs JDK 25 (`JAVA_HOME` pointing at it).

| | Version |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.155.3+26.1.2 |
| Loom | 1.19.4 |
| Gradle | 9.9.1 (wrapper) |

## License

All rights reserved.

*Telemetric is a fan-made mod and is not affiliated with Telos Realms or Mojang.*
