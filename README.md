# Chunkport

**Chunkport** converts modern Minecraft Java worlds and schematics **down to Minecraft 1.7.10**, targeting modded servers that use [NotEnoughIDs](https://github.com/GTNewHorizons/NotEnoughIDs) block IDs above 4095.

It is a fork of [HiveGames' Chunker](https://github.com/HiveGamesOSS/Chunker) specialised for one job: taking builds made in any modern version (1.8 → 1.21+) and producing faithful 1.7.10 output, remapping modern blocks onto the mod blocks of *your* server (EtFuturum, UpToDate, Netherlicious, ...) using a simple text mapping file and the server's own block ID registry.

```
.schematic (1.12-)  ┐
.schem  (Sponge 1-3)├──►  1.7.10 .schematic  (with NEID AddBlocks2/AddData)
.litematic          ┘
modern Java world  ────►  1.7.10 Anvil world
```

The upstream Chunker formats (Bedrock, other Java versions) remain in the codebase,
but Chunkport is designed, tested and supported for the **`JAVA_1_7_10`** target only.

---

## Quick start

1. Put these in one folder:
   ```
   Converter.bat            <- the runner (dist/Converter.bat)
   chunker-cli-x.y.z.jar    <- the Chunkport jar
   plug.dat                 <- the level.dat of YOUR 1.7.10 server (block ID registry)
   mapping.txt              <- your block mapping rules (see below)
   input_schematic\         <- drop .schem / .schematic / .litematic files here
   input_world\             <- or drop the contents of a world folder here
   ```
2. Double-click **`Converter.bat`** and pick a mode. It finds Java 17+ by itself
   (JAVA_HOME → PATH → common install folders) and checks every file before running.
3. Results appear in `output_schematic\` / `output_world\`. Watch the console for
   `[warn]` lines — they list blocks that had no 1.7.10 mapping and became air.

> `Converter.bat schem` or `Converter.bat world` skips the menu (for scripts).

**Requirements**

| What | Why |
|---|---|
| Java 17+ | runs the converter (the runner locates it automatically) |
| `plug.dat` | the target server's `level.dat` — its FML registry maps names like `etfuturum:tuff` to numeric IDs |
| `mapping.txt` | your rules for turning modern blocks into your server's mod blocks |
| NEID-aware WorldEdit | to paste the output, the server's WorldEdit must read `AddBlocks2` (see [worldedit-gtnh](https://github.com/KAMKEEL/worldedit-gtnh)) |

---

## Schematic conversion

```
java -jar chunker-cli.jar --input-schem <folder> -f JAVA_1_7_10 --output-schem <folder>
     --enableNEIDs --legacySimpleMappings
     --levelConvert plug.dat --simpleBlockMappings mapping.txt
```

Input formats (auto-detected, may be mixed in one folder, subfolders included):

| Format | Versions | Notes |
|---|---|---|
| `.schematic` | classic MCEdit/WorldEdit (≤1.12) | numeric IDs, `AddBlocks`/`AddBlocks2`/`AddData` supported |
| `.schem` | Sponge v1, v2 and v3 (1.13 → 1.21+) | palette resolved per the file's `DataVersion` |
| `.litematic` | Litematica v4-v7 | all sub-regions composed into one schematic |

Output is always a classic **1.7.10 `.schematic`**: `AddBlocks` for IDs 256–4095,
`AddBlocks2` for NEID IDs above 4095, `AddData` for data values above 15, and the
WorldEdit paste anchor (`WEOffsetX/Y/Z`) preserved from the source file.

Rotations and block states survive the trip: stair facings, log axes, slab halves,
door hinges, repeater delays, rail shapes, vine faces and so on are translated to
the correct legacy data values.

## World conversion

```
java -jar chunker-cli.jar -i <world folder> -f JAVA_1_7_10 -o <output folder>
     --enableNEIDs --legacySimpleMappings
     --levelConvert plug.dat --simpleBlockMappings mapping.txt
```

---

## plug.dat — the block ID registry

`--levelConvert` takes the **`level.dat` of the destination 1.7.10 server**. Its FML
`ItemData` section is the authoritative name → numeric ID registry for every block,
vanilla and modded. Whenever a mapping produces a name like `uptodate:grass_path`,
Chunkport looks the numeric ID up here first, falling back to the built-in vanilla
tables. Without it, mod blocks cannot resolve and the converter tells you so.

---

## mapping.txt reference

One rule per line. `#` starts a comment, blank lines are ignored.

```
<source> -> <target>
<source> -> <target> -> <STATE_GROUP>
```

### Source (left side)

| Form | Example | Matches |
|---|---|---|
| name | `minecraft:mob_spawner` | the block with any data value |
| name + data | `minecraft:stone[data=1]` | only that exact data value (granite) — plain `stone` is untouched |
| numeric ID | `52` | legacy block ID 52, any data |
| numeric ID:meta | `1:1` | legacy block ID 1 with data 1 only |

Sources may be 1.12-era names (`minecraft:mob_spawner`) **or** modern flattened names
(`minecraft:wet_sponge`, `minecraft:observer`) — modern inputs are resolved through the
version-aware tables first, so one rule covers every input format.

### Target (right side)

| Form | Example | Result |
|---|---|---|
| name | `etfuturum:observer` | ID looked up in plug.dat (then vanilla tables); source data value carried over |
| name + data | `uptodate:sponge[data=1]` | ID looked up, data forced to 1 |
| numeric ID | `2001` | that exact ID, source data carried over |
| numeric ID:meta | `126:14` | that exact ID and data |
| `=name` | `=minecraft:stone_slab -> uptodate:slab_stone` | fallback-style mapping used by the resolvers; output still resolves through plug.dat |

Rule precedence: **name rules win over numeric rules**, and a data-specific rule
(`stone[data=1]`) only fires on that data value. A rule's explicit `[data=N]` always
overrides; otherwise the source block's data value is preserved.

### `-> STATE_GROUP` shortcuts

Adding a third segment computes the 1.7.10 **data value from the modern block's
state properties** (facing, half, open, delay, ...), so one line handles every
orientation:

```
minecraft:mud_brick_stairs  -> etfuturum:mud_brick_stairs  -> STAIRS
minecraft:crimson_door      -> netherlicious:CrimsonDoor   -> DOOR
minecraft:mangrove_log      -> etfuturum:mangrove_log      -> AXIS
```

Valid group names (they mirror the legacy data layouts of vanilla 1.7.10):

| Category | Groups |
|---|---|
| Orientation | `AXIS` `FACING_ALL` `FACING_HORIZONTAL_SWNE` `FACING_HORIZONTAL_UNUSUAL` `FACING_POWERED` `FACING_TRIGGERED` `ROTATION` `STEM_FACING` `TORCH_DIRECTION` |
| Building blocks | `STAIRS` `SLAB_HALF` `WALL` `WOOD` `LEAVES` `SNOW` `SNOWY` |
| Doors & gates | `DOOR` `DOOR_2` `TRAPDOOR` `FENCE_GATE` |
| Redstone | `BUTTON` `LEVER` `REPEATER` `POWERED_COMPARATOR` `UNPOWERED_COMPARATOR` `POWER` `POWERED` `REDSTONE_WIRE` `TRIPWIRE` `TRIPWIRE_HOOK` `NOTE_BLOCK` `COMMAND_BLOCK` |
| Rails & pistons | `RAIL` `POWERED_RAIL` `PISTON` `PISTON_EXTENSION` `PISTON_HEAD` |
| Containers & utility | `CHEST` `HOPPER` `BREWING_STAND` `CAULDRON` `JUKEBOX` `FARMLAND` `BED` `CAKE` `SKULL` `WALL_SKULL` `END_PORTAL_FRAME` |
| Plants & growth | `AGE_3` `AGE_5` `AGE_7` `AGE_15` `AGE_3_TO_7` `COCOA` `VINE` `MUSHROOM_BLOCK` |
| Misc | `BEDROCK` `CONNECTABLE` `CONNECTABLE_HORIZONTAL` `FIRE` `LIQUID` `NETHER_PORTAL` `STRUCTURE_BLOCK` `STRUCTURE_VOID` `TNT` |

An explicit `[data=N]` on the target overrides the group's computed value, which is
how half-specific lines work:

```
minecraft:warped_slab[type=bottom] -> netherlicious:PlankSingleSlab[data=1]
minecraft:warped_slab[type=top]    -> netherlicious:PlankSingleSlab[data=9]
```

### Worked examples

```
# Remove broken blocks entirely
minecraft:mob_spawner -> minecraft:air

# Stone variants: granite/diorite/... move to the mod block, plain stone stays vanilla
minecraft:stone[data=1] -> uptodate:stone[data=1]

# Modern one-block-per-variant names fold onto data values
minecraft:wet_sponge -> uptodate:sponge[data=1]

# Orientation handled automatically
minecraft:blackstone_stairs -> netherlicious:BlackstoneStairs -> STAIRS
```

`--convertMapping --simpleBlockMappings mapping.txt --levelConvert plug.dat` compiles
your mapping file to `generated.json` for inspection.

---

## CLI flags

| Flag | Purpose |
|---|---|
| `--input-schem <dir>` / `--output-schem <dir>` | schematic conversion mode |
| `-i <dir>` / `-o <dir>` / `-f JAVA_1_7_10` | world conversion mode |
| `--levelConvert <level.dat>` | target server block ID registry (plug.dat) |
| `--simpleBlockMappings <mapping.txt>` | block mapping rules (this document's format) |
| `--blockMappings <json/file>` | raw Chunker JSON mappings (advanced) |
| `--enableNEIDs` | emit `AddBlocks2`/`AddData` for IDs > 4095 / data > 15 |
| `--legacySimpleMappings` | data-value aware rule matching (enabled automatically when a mapping file is given) |
| `--convertMapping` | compile mapping.txt to generated.json and exit |
| `--debug` | verbose logging |

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `[warn] ...: N palette entries could not be mapped (converted to air)` | those modern blocks have no 1.7.10 equivalent and no rule — add lines to mapping.txt or accept air |
| Pasted blocks are *wrong* mod/vanilla blocks (e.g. ore instead of bricks) | the server's WorldEdit doesn't read `AddBlocks2` — every ID is truncated to 12 bits. Update to a NEID-aware WorldEdit ([worldedit-gtnh](https://github.com/KAMKEEL/worldedit-gtnh)); on hybrid servers the Bukkit WorldEdit plugin and AsyncWorldEdit need the fix too |
| `ERROR: --levelConvert level.dat not found` | fix the path, or copy the server's `level.dat` next to the jar as `plug.dat` |
| `WARNING: no FML block ID mappings found in ...` | the file isn't a Forge server level.dat (no `FML → ItemData` section) |
| Paste appears offset from where you stand | the source schematic's copy anchor is preserved (`WEOffset`); that is faithful WorldEdit behaviour |
| Signs/chests are empty | entities and tile entities are not converted yet (known limitation) |

---

## Building

```
gradlew :cli:shadowJar    ->  cli/build/libs/chunker-cli-<version>.jar
gradlew :cli:test
```

## Credits

Chunkport is a fork of [Chunker](https://github.com/HiveGamesOSS/Chunker) by Hive Games,
used under its license. All credit for the core conversion engine belongs to the
Chunker team; Chunkport adds the 1.7.10 target, NotEnoughIDs support, the level.dat
registry, the simple mapping system and the schematic/litematic pipeline.
