# Telemetric

Client-side [Fabric](https://fabricmc.net/) utility mod for the **Telos Realms** Minecraft server, with a fully
animated ClickGUI, movable HUD elements and dungeon/stash tools.


Discord: https://discord.gg/dqjD94x3Ra

## Features

| Category | Feature | What it does |
|---|---|---|
| Player | **No Jump Delay** | Hold jump to jump again the instant you land (no vanilla jump delay) |
| Player | **Auto Sprint** | Always sprint while moving forward |
| Combat | **Weapon / Ability / Armor Cooldowns** | Cooldown bars beside the crosshair and on your armor, with "Ready!" popups and sounds |
| Combat | **Weapon / Ability Range** | Range of the held weapon / ability drawn on the ground |
| Combat | **Assassin Stacks / Auto Clicker** | Assassin stack counter; hold attack to keep attacking |
| Visual | **Fullbright / Camera** | See in the dark; no front camera, optional auto third person while looking down |
| Visual | **Hitboxes / Player Size / Hide Armor** | Entity hitboxes; bigger or smaller players; hide worn armor, peek at real items under skins |
| Visual | **Item Rarity / Health Bar / Boss Bar Scale** | Rarity colour behind items; your health above your head or on the HUD; smaller boss bars |
| Visual | **Custom Bags** | Your own picture (incl. GIF) and sound for loot bag drops |
| Combat | **Low Health Warning** | Big warning text and a sound when your health gets low |
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

## Credits

- **[Melinoe](https://github.com/House-Hades/Melinoe)** (NoWayItzJoey, Willow, Magnetite, Nyxie) — the badge font
  textures `assets/telemetric/textures/font/pill.png` and `ribbon.png`, and the bitmap-font badge technique used for
  floating world labels. Melinoe is licensed under
  [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html); those two textures remain under that license.
  The **Combat** features (weapon / ability / armor cooldowns and ready alerts, weapon / ability range, Assassin
  stacks, Auto Clicker) and Auto Sprint are based on Melinoe's Combat tab, and the **Visual** features (Fullbright,
  Camera, Boss Bar Scale, Hitboxes, Player Size, Hide Armor, Item Rarity, Health Bar, Custom Bags, Low Health Warning)
  on its Visual tab — re-implemented for Telemetric with the authors' permission. The custom bag models
  (`assets/telemetric/models/bags/`, `items/bags/`) and textures (`textures/mob/bags/`) and the Telos data tables in
  the [data repo](https://github.com/Pryxo/Telemetric-Data) (`telos/`) come from Melinoe and remain under LGPL-3.0.

## License

All rights reserved, except third-party assets listed under [Credits](#credits).

*Telemetric is a fan-made mod and is not affiliated with Telos Realms or Mojang.*
