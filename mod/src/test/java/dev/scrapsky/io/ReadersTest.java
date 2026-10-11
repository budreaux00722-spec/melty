package dev.scrapsky.io;

import dev.scrapsky.games.NoMansSkyLibrary;
import dev.scrapsky.games.PlanetNames;
import dev.scrapsky.games.ScrapMechanicLibrary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** End-to-end checks of the game readers on synthetic files. Args: workDir wemLong wemShort codebooks */
public final class ReadersTest {
    static int fails = 0;
    static void check(boolean ok, String what) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        Path work = Path.of(a[0]);
        byte[] wemLong = Files.readAllBytes(Path.of(a[1])), wemShort = Files.readAllBytes(Path.of(a[2]));
        var books = new WemToOgg.CodebookLibrary(Files.readAllBytes(Path.of(a[3])));

        // ---------- HGPAK both modes ----------
        byte[] big = new byte[300_000];
        new java.util.Random(1).nextBytes(big);
        for (boolean comp : new boolean[]{true, false}) {
            Path p = work.resolve("t" + comp + ".pak");
            FixtureWriters.hgpak(p, FixtureWriters.map("A/SMALL.TXT", "hello".getBytes(), "B/BIG.BIN", big, "C/X.TXT", "xyz".getBytes()), comp);
            try (HgPak hp = new HgPak(p)) {
                check(hp.entries().size() == 3, "hgpak(" + comp + ") lists 3 files");
                check(new String(hp.read(hp.entries().get("a/small.txt"))).equals("hello"), "hgpak(" + comp + ") small file");
                check(java.util.Arrays.equals(hp.read(hp.entries().get("b/big.bin")), big), "hgpak(" + comp + ") 300 KB file across chunks");
                check(new String(hp.read(hp.entries().get("c/x.txt"))).equals("xyz"), "hgpak(" + comp + ") file after big one");
            }
        }

        // ---------- fake No Man's Sky ----------
        Path nms = work.resolve("NMS");
        Map<String, String> loc = new LinkedHashMap<>();
        loc.put("UI_PARADISE_PLANET", "Paradise Planet");
        loc.put("UI_BIOME_FROZEN_1", "Frozen World");
        loc.put("UI_TOXIC_PLANET", "Caustic Moon");
        loc.put("UI_WEATHER_ACID_RAIN", "Acid Rain");
        loc.put("UI_WEATHER_MILD", "Mild Breeze");
        String[] lore = {"Korvax", "Atlas", "Artemis", "Nada", "Polo", "Apollo", "Vykeen", "Gekko", "Sentinel", "Euclid", "Hilbert", "Calypso", "Hesperius", "Hyades", "Ickjamatew", "Budullangr", "Loychazor", "Zukasizawa", "Ekwathore", "Rerasmutul", "Isdoraijung", "Doctinawyra", "Iousongola", "Yuhordraso"};
        for (int i = 0; i < lore.length; i++) loc.put("LORE_" + i, "The " + lore[i] + " walked the path of the " + lore[(i + 3) % lore.length] + " and the stars.");
        FixtureWriters.hgpak(nms.resolve("GAMEDATA/PCBANKS/NMSARC.Language.pak"), FixtureWriters.map("LANGUAGE/NMS_LOC1_ENGLISH.MBIN", FixtureWriters.locMbin(loc, 2)), true);
        FixtureWriters.hgpak(nms.resolve("GAMEDATA/PCBANKS/NMSARC.audio.pak"), FixtureWriters.map("AUDIO/WINDOWS/111.WEM", wemShort, "AUDIO/WINDOWS/222.WEM", wemLong), true);

        StringBuilder logs = new StringBuilder();
        NoMansSkyLibrary lib = new NoMansSkyLibrary(nms, s -> logs.append(s).append('\n'));
        check(lib.localisation().size() == loc.size(), "NMS localisation read (" + lib.localisation().size() + ")");
        check(lib.descriptor("d_lush", 1).equals("Paradise Planet"), "lush descriptor from key match");
        check(lib.descriptor("d_frozen", 1).equals("Frozen World"), "frozen descriptor");
        check(lib.descriptor("d_toxic", 1).equals("Caustic Moon"), "toxic descriptor");
        String w = lib.descriptor("d_weather", 5);
        check(w.equals("Acid Rain") || w.equals("Mild Breeze"), "weather descriptor: " + w);
        List<String> vocab = lib.vocabulary();
        check(vocab.contains("korvax") && !vocab.contains("walked") && !vocab.contains("stars"), "vocabulary keeps proper nouns only (" + vocab.size() + ")");
        PlanetNames names = new PlanetNames(vocab);
        String n1 = names.name(42), n2 = names.name(42), n3 = names.name(43);
        check(n1 != null && n1.equals(n2), "planet name stable per seed: " + n1);
        check(n3 != null, "another planet: " + n3);
        List<Path> music = lib.music(work.resolve("cache"), 6, books);
        check(music.size() == 1, "one track >= 60 s converted (" + music.size() + ")");
        check(music.size() == 1 && new String(Files.readAllBytes(music.get(0)), 0, 4, StandardCharsets.US_ASCII).equals("OggS"), "track is Ogg");
        List<Path> again = lib.music(work.resolve("cache"), 6, books);
        check(again.equals(music) && logs.toString().contains("cached"), "music cache reused");

        // ---------- fake Scrap Mechanic ----------
        Path sm = work.resolve("SM");
        String blocks = """
            // comment like the real files
            { "blockList": [
              { "uuid": "df953d9c-234f-4ac2-af5e-f0490b223e71", "name": "blk_wood1", "dif": "$GAME_DATA/Objects/Textures/Blocks/blk_wood01_dif.tga", "color": "9f7956", "tiling": 4, },
              { "uuid": "5f41af56-df4c-4837-9b3c-10781335757f", "name": "blk_glass", "dif": "$GAME_DATA/Objects/Textures/Blocks/blk_glass_dif.tga", "color": "e4f8ff", "tiling": 4 }
            ] }""";
        String parts = """
            { "partList": [
              { "uuid": "aaaaaaaa-0000-0000-0000-000000000001", "name": "obj_interactive_thruster_01",
                "renderable": "$SURVIVAL_DATA/Objects/Renderable/interactive/obj_interactive_thruster_01.rend", "color": "c25b2a" }
            ] }""";
        String rend = """
            { "lodList": [ { "mesh": "$SURVIVAL_DATA/Objects/Mesh/interactive/obj_interactive_thruster_01.fbx",
              "subMeshMap": { "body": { "textureList": [ "$SURVIVAL_DATA/Objects/Textures/interactive/obj_interactive_thruster_01_dif.tga", "x_asg.tga" ] } } } ] }""";
        String lang = """
            { "df953d9c-234f-4ac2-af5e-f0490b223e71": { "title": "Wood Block 1", "description": "..." },
              "aaaaaaaa-0000-0000-0000-000000000001": { "title": "Thruster", "description": "..." } }""";
        write(sm.resolve("Data/Objects/Database/ShapeSets/blocks.json"), blocks);
        write(sm.resolve("Survival/Objects/Database/ShapeSets/interactive.shapeset"), parts);
        write(sm.resolve("Survival/Objects/Renderable/interactive/obj_interactive_thruster_01.rend"), rend);
        write(sm.resolve("Data/Gui/Language/English/InventoryItemDescriptions.json"), lang);
        Files.createDirectories(sm.resolve("Data/Objects/Textures/Blocks"));
        Files.write(sm.resolve("Data/Objects/Textures/Blocks/blk_wood01_dif.tga"), FixtureWriters.tga(256, 256, 0xff808080, 0xffa0a0a0));
        Files.write(sm.resolve("Data/Objects/Textures/Blocks/blk_glass_dif.tga"), FixtureWriters.tga(64, 64, 0x40ffffff, 0x60ffffff));
        Files.createDirectories(sm.resolve("Survival/Objects/Textures/interactive"));
        Files.write(sm.resolve("Survival/Objects/Textures/interactive/obj_interactive_thruster_01_dif.tga"), FixtureWriters.tga(128, 128, 0xff303030, 0xff905020));

        ScrapMechanicLibrary sml = new ScrapMechanicLibrary(sm, s -> logs.append(s).append('\n'));
        var res = sml.resolveAll();
        check(res.containsKey("hull_wood") && res.get("hull_wood").title().equals("Wood Block 1") && res.get("hull_wood").how().equals("uuid"), "wood resolved by uuid with its name");
        check(res.get("hull_wood").face() != null && res.get("hull_wood").face().w() == 32, "wood face texture 32x32");
        check(res.containsKey("hull_glass") && ((res.get("hull_glass").face().at(3, 3) >>> 24) < 255), "glass keeps transparency");
        check(res.containsKey("thruster") && res.get("thruster").title().equals("Thruster") && res.get("thruster").how().equals("texture"), "thruster resolved by its .rend texture name");
        check(res.get("thruster").face() != null, "thruster face from its own texture");
        check(!res.containsKey("hull_concrete"), "missing part falls back to placeholder");
        Files.write(work.resolve("wood_face.png"), Images.png(res.get("hull_wood").face()));

        System.out.println(logs);
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static void write(Path p, String s) throws Exception { Files.createDirectories(p.getParent()); Files.writeString(p, s); }
}
