# Scrap Sky

**Minecraft × Scrap Mechanic × No Man's Sky.** Build ships in Minecraft from Scrap Mechanic's real parts and fly them to planets carrying No Man's Sky's own music and names.

You play in **Minecraft: Java Edition**. The other two games are really in it, read from your own copies while you play:

- **Scrap Mechanic** brings the parts: wood, metal, concrete, glass, brick and caution blocks, the Thruster and the Driver's Seat, with their names, textures and colours read from your Scrap Mechanic install.
- **No Man's Sky** brings the planets' soundtrack (its own music, converted on your PC from your install), the planet descriptors and weather lines (its own English text), and the words each world's planet names are generated from.

Nothing from either game is included in the download.

## Features
- Start beside a crash-landed ship built from Scrap Mechanic parts. Both of its thrusters are broken.
- Repair a thruster with 3 iron ingots and 2 redstone, sit in the Driver's Seat and take off: every Scrap Sky part touching the seat flies as one ship.
- Fly with W/S, turn with A/D, climb with Space, descend with Ctrl, and land with Shift: the ship turns back into blocks where it lands.
- Climb above y=300 to reach orbit and pick a destination on the Star Map: three planets (lush, frozen, toxic), each with a name, a No Man's Sky descriptor and weather, and No Man's Sky's music. Or go back Home.
- Craft more parts from Minecraft materials and build bigger ships: more thrusters lift more blocks and fly faster.
- **Play alone or with up to 4 friends.** The host's world opens to friends automatically through the bundled e4mc relay (no port forwarding), and Melty's Join button puts friends straight in.

## Requirements
Minecraft: Java Edition, Scrap Mechanic and No Man's Sky (PC). On Melty, press Play: it installs Minecraft 1.21.1 with Fabric in a bundled Prism Launcher and finds your other two games.

## How it is built
- `design/sheets/*.json`: the design. Every part, planet, system, hook, control and test is a row. `tools/gen_sheets.py` generates the Java records and the mod's data files from them, and `tools/preflight.py` checks every cell and cross-reference before a build.
- `mod/`: the Fabric mod (Minecraft 1.21.1, Java 21). It reads Scrap Mechanic's JSON shapesets, language files and TGA/DDS textures, and No Man's Sky's HGPAK archives (zstd), localisation MBINs and Wwise WEM music (converted to Ogg by a Java port of ww2ogg).
- `packaging/`: the Prism Launcher instance and the Melty recipe (`melty.json`).

Credits are in [CREDITS.md](CREDITS.md). License: MIT; remixes welcome.
