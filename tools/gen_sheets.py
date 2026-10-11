#!/usr/bin/env python3
"""Generate code and data from design/sheets. Every row becomes one Java
record instance (SheetData.java) and, where it applies, its data files
(block models, recipes, biomes, dimensions, fallback names and textures).
Run after editing a sheet; never edit generated files by hand."""
import json, os, struct, zlib, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S = {json.load(open(p))["sheet"]: json.load(open(p)) for p in glob.glob(os.path.join(ROOT, "design/sheets/*.json"))}
MOD = os.path.join(ROOT, "mod/src/main")
RES = os.path.join(MOD, "resources")
A = os.path.join(RES, "assets/scrapsky")
D = os.path.join(RES, "data/scrapsky")
JGEN = os.path.join(MOD, "java/dev/scrapsky/gen")

def rows(n): return S[n]["rows"]
def w(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if isinstance(data, (dict, list)):
        data = json.dumps(data, indent=2) + "\n"
    mode = "wb" if isinstance(data, bytes) else "w"
    with open(path, mode) as f: f.write(data)
def jstr(s): return json.dumps(s)
def jarr(a): return "new String[]{" + ", ".join(jstr(x) for x in a) + "}"

def png(color_hex, size=16, variant=0):
    r, g, b = int(color_hex[0:2], 16), int(color_hex[2:4], 16), int(color_hex[4:6], 16)
    raw = b""
    for y in range(size):
        raw += b"\0"
        for x in range(size):
            edge = x in (0, size - 1) or y in (0, size - 1)
            k = 0.78 if edge else (1.0 if (x + y + variant) % 7 else 0.92)
            raw += bytes([min(255, int(r * k)), min(255, int(g * k)), min(255, int(b * k)), 255])
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

# ---------- parts -> blocks ----------
lang = {"itemGroup.scrapsky": "Scrap Sky", "entity.scrapsky.ship": "Ship"}
for p in rows("parts"):
    pid = p["id"]
    facing = p["kind"] in ("thruster", "broken_thruster", "seat")
    if facing:
        w(f"{A}/blockstates/{pid}.json", {"variants": {f"facing={f}": {"model": f"scrapsky:block/{pid}", **({"y": y} if y else {})}
                                                       for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
    else:
        w(f"{A}/blockstates/{pid}.json", {"variants": {"": {"model": f"scrapsky:block/{pid}"}}})
    if p["kind"] == "glass":
        w(f"{A}/models/block/{pid}.json", {"parent": "minecraft:block/cube_all", "render_type": "minecraft:translucent", "textures": {"all": f"scrapsky:block/{pid}"}})
    elif p["kind"] in ("thruster", "broken_thruster"):
        # nozzle side (north) shows the part texture darkened by the runtime pack as <id>_front
        w(f"{A}/models/block/{pid}.json", {"parent": "minecraft:block/orientable", "textures": {"front": f"scrapsky:block/{pid}_front", "side": f"scrapsky:block/{pid}", "top": f"scrapsky:block/{pid}"}})
        w(f"{A}/textures/block/{pid}_front.png", png(p["fallbackColor"], variant=3))
    elif p["kind"] == "seat":
        w(f"{A}/models/block/{pid}.json", {"parent": "minecraft:block/orientable", "textures": {"front": f"scrapsky:block/{pid}_front", "side": f"scrapsky:block/{pid}", "top": f"scrapsky:block/{pid}"}})
        w(f"{A}/textures/block/{pid}_front.png", png(p["fallbackColor"], variant=5))
    else:
        w(f"{A}/models/block/{pid}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"scrapsky:block/{pid}"}})
    w(f"{A}/models/item/{pid}.json", {"parent": f"scrapsky:block/{pid}"})
    w(f"{A}/textures/block/{pid}.png", png(p["fallbackColor"]))
    lang[f"block.scrapsky.{pid}"] = p["fallbackName"]
    w(f"{D}/loot_table/blocks/{pid}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"scrapsky:{pid}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})

pick = {"hardness": [], "kinds": {}}
for p in rows("parts"):
    tool = "pickaxe" if p["sound"] in ("metal", "stone") else ("axe" if p["sound"] == "wood" else None)
    if tool: pick.setdefault(tool, []).append(f"scrapsky:{p['id']}")
for tool in ("pickaxe", "axe"):
    if tool in pick:
        w(f"{RES}/data/minecraft/tags/block/mineable/{tool}.json", {"replace": False, "values": pick[tool]})

# ---------- recipes ----------
for r in rows("recipes"):
    key = {k: ({"tag": v[1:]} if v.startswith("#") else {"item": v}) for k, v in r["key"].items()}
    w(f"{D}/recipe/{r['result']}.json", {"type": "minecraft:crafting_shaped", "category": "building", "pattern": r["pattern"], "key": key, "result": {"id": f"scrapsky:{r['result']}", "count": r["count"]}})

# ---------- planets -> biomes, dimensions ----------
def color(h): return int(h, 16)
for pl in rows("planets"):
    if pl["kind"] != "planet": continue
    bid = pl["biome"].split(":")[1]
    did = pl["dimension"].split(":")[1]
    veg = {"lush": ["minecraft:trees_jungle", "minecraft:flower_warm", "minecraft:patch_grass_jungle", "minecraft:patch_melon"],
           "frozen": ["minecraft:trees_snowy", "minecraft:patch_grass_plain"],
           "toxic": ["minecraft:trees_swamp", "minecraft:patch_grass_normal", "minecraft:brown_mushroom_swamp", "minecraft:red_mushroom_swamp", "minecraft:patch_waterlily"]}[pl["id"]]
    # 11 generation steps: raw, lakes, local, underground structures, surface structures, strongholds,
    # underground ores, underground decoration, fluid springs, vegetal decoration, top layer
    features = [[], [], [], [], [], [],
                ["minecraft:ore_coal_upper", "minecraft:ore_coal_lower", "minecraft:ore_iron_upper", "minecraft:ore_iron_middle", "minecraft:ore_iron_small", "minecraft:ore_gold", "minecraft:ore_redstone", "minecraft:ore_diamond", "minecraft:ore_copper"],
                [], ["minecraft:spring_water"], veg, ["minecraft:freeze_top_layer"]]
    w(f"{D}/worldgen/biome/{bid}.json", {
        "has_precipitation": pl["id"] != "toxic",
        "temperature": pl["temperature"], "downfall": pl["downfall"],
        "effects": {"sky_color": color(pl["skyColor"]), "fog_color": color(pl["fogColor"]), "water_color": color(pl["waterColor"]),
                    "water_fog_color": color(pl["waterColor"]), "grass_color": color(pl["grassColor"]), "foliage_color": color(pl["foliageColor"]),
                    "music": {"sound": pl["music"], "min_delay": 600, "max_delay": 2400, "replace_current_music": True}},
        "spawners": {"monster": [{"type": "minecraft:zombie", "weight": 60, "minCount": 1, "maxCount": 3}, {"type": "minecraft:spider", "weight": 40, "minCount": 1, "maxCount": 2}],
                     "creature": [{"type": "minecraft:sheep", "weight": 12, "minCount": 2, "maxCount": 4}, {"type": "minecraft:cow", "weight": 8, "minCount": 2, "maxCount": 4}],
                     "ambient": [], "axolotls": [], "underground_water_creature": [], "water_creature": [], "water_ambient": [], "misc": []},
        "spawn_costs": {},
        "carvers": {"air": ["minecraft:cave", "minecraft:cave_extra_underground", "minecraft:canyon"]},
        "features": features})
    w(f"{D}/dimension/{did}.json", {"type": "scrapsky:planet", "generator": {"type": "minecraft:noise", "settings": pl["terrain"], "biome_source": {"type": "minecraft:fixed", "biome": pl["biome"]}}})
w(f"{D}/dimension_type/planet.json", {"ultrawarm": False, "natural": True, "piglin_safe": False, "respawn_anchor_works": False, "bed_works": True, "has_raids": False,
    "has_skylight": True, "has_ceiling": False, "coordinate_scale": 1.0, "ambient_light": 0.0, "logical_height": 384,
    "effects": "minecraft:overworld", "infiniburn": "#minecraft:infiniburn_overworld", "min_y": -64, "height": 384,
    "monster_spawn_light_level": {"type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7}, "monster_spawn_block_light_limit": 0})

w(f"{A}/lang/en_us.json", {**lang,
    "scrapsky.msg.wreck": "Your ship crash-landed nearby. Fix its Broken Thruster: use it with 3 iron ingots and 2 redstone in your inventory. Then use the Driver's Seat to fly.",
    "scrapsky.msg.repaired": "Thruster repaired!",
    "scrapsky.msg.need_parts": "Repairing needs 3 iron ingots and 2 redstone.",
    "scrapsky.msg.no_thruster": "This ship needs at least one working Thruster.",
    "scrapsky.msg.too_heavy": "Too heavy to lift: mass %s, thrust %s. Add Thrusters or remove blocks.",
    "scrapsky.msg.too_big": "Too many parts (max %s).",
    "scrapsky.msg.cant_land": "Find open ground to land.",
    "scrapsky.msg.full": "This Scrap Sky world is full (%s players).",
    "scrapsky.msg.flying": "W/S fly, A/D turn, Space climb, Ctrl descend, Shift land. Climb above y=300 to reach orbit.",
    "scrapsky.starmap.title": "Star Map",
    "scrapsky.starmap.home": "Home",
    "scrapsky.starmap.go": "Fly to %s"})

# ---------- Java ----------
def part_java(p):
    return (f"        new Part({jstr(p['id'])}, Kind.{p['kind'].upper()}, {jarr(p['smUuids'])}, {jstr(p['smTextureMatch'])}, {jstr(p['smNameMatch'])}, "
            f"{jstr(p['fallbackName'])}, 0x{p['fallbackColor']}, {float(p['hardness'])}f, {jstr(p['sound'])}, {p['mass']}, {p['lift']}, {str(p['inStarterWreck']).lower()})")
def planet_java(p):
    return (f"        new Planet({jstr(p['id'])}, {jstr(p['dimension'])}, {str(p['kind'] == 'home').lower()}, {jstr(p['descriptorRef'])}, {p['nameSalt']})")
def nms_java(r):
    return (f"        new NmsRow({jstr(r['id'])}, {jstr(r['pak'])}, {jstr(r['fileMatch'])}, {jstr(r['keyMatch'])}, {jstr(r['valueMatch'])}, {jstr(r['fallback'])})")
mp = {r["id"]: r["value"] for r in rows("multiplayer")}
java = f'''// GENERATED by tools/gen_sheets.py from design/sheets. Do not edit by hand.
package dev.scrapsky.gen;

import java.util.List;

public final class SheetData {{
    private SheetData() {{}}

    public enum Kind {{ HULL, GLASS, THRUSTER, BROKEN_THRUSTER, SEAT }}

    /** sheet "parts": one Scrap Mechanic part that becomes a Minecraft block. */
    public record Part(String id, Kind kind, String[] smUuids, String smTextureMatch, String smNameMatch,
                       String fallbackName, int fallbackColor, float hardness, String sound, int mass, int lift,
                       boolean inStarterWreck) {{}}

    /** sheet "planets": a place the ship can fly to. */
    public record Planet(String id, String dimension, boolean home, String descriptorRef, int nameSalt) {{}}

    /** sheet "nms_content": what is read from No Man's Sky and how it is picked. */
    public record NmsRow(String id, String pak, String fileMatch, String keyMatch, String valueMatch, String fallback) {{}}

    public static final List<Part> PARTS = List.of(
{",\n".join(part_java(p) for p in rows("parts"))}
    );

    public static final List<Planet> PLANETS = List.of(
{",\n".join(planet_java(p) for p in rows("planets"))}
    );

    public static final List<NmsRow> NMS = List.of(
{",\n".join(nms_java(r) for r in rows("nms_content"))}
    );

    public static final int MAX_PLAYERS = {mp["max_players"]};

    public static Part part(String id) {{
        for (Part p : PARTS) if (p.id().equals(id)) return p;
        throw new IllegalArgumentException(id);
    }}

    public static NmsRow nms(String id) {{
        for (NmsRow r : NMS) if (r.id().equals(id)) return r;
        throw new IllegalArgumentException(id);
    }}
}}
'''
w(f"{JGEN}/SheetData.java", java)
print("generated", len(rows("parts")), "parts,", len(rows("recipes")), "recipes,", len(rows("planets")), "planets")
