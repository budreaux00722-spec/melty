package dev.scrapsky.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads a No Man's Sky localisation MBIN (cTkLocalisationTable) into id -> text.
 * Layout from monkeyman192/MBINCompiler (libMBIN): a 0x20-byte header, then the table's
 * list header (int64 offset relative to itself, int32 count, uint32 0xAAAAAA01 magic) at 0x20.
 * Each TkLocalisationEntry is 0x130 bytes: a 0x20-byte ASCII id followed by seventeen
 * 0x10-byte VariableSizeStrings (one per language). Per-language files fill only one slot,
 * so the first non-empty slot is taken (English files hold English).
 */
public final class Mbin {
    private Mbin() {}

    public static final long MAGIC = 0xCCCCCCCCCCCCCCCCL;
    private static final int ENTRY = 0x130;
    private static final int SLOTS = 17;

    public static Map<String, String> readLocalisation(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 0x30 || b.getLong(0) != MAGIC) throw new IllegalArgumentException("not an MBIN");
        int listPos = 0x20;
        long rel = b.getLong(listPos);
        int count = b.getInt(listPos + 8);
        int magic = b.getInt(listPos + 12);
        if ((magic & 0xff) != 1) throw new IllegalArgumentException("not a list at 0x20");
        long start = listPos + rel;
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            long e = start + (long) i * ENTRY;
            if (e + ENTRY > data.length) break;
            String id = cstr(data, (int) e, 0x20);
            if (id.isEmpty()) continue;
            String text = null;
            for (int s = 0; s < SLOTS && text == null; s++) {
                int f = (int) e + 0x20 + s * 0x10;
                long off = b.getLong(f);
                int len = b.getInt(f + 8);
                if (len <= 0 || off == 0) continue;
                long at = f + off;
                if (at < 0 || at + len > data.length) continue;
                String v = new String(data, (int) at, len, StandardCharsets.UTF_8);
                int nul = v.indexOf('\0');
                if (nul >= 0) v = v.substring(0, nul);
                if (!v.isBlank()) text = v;
            }
            if (text != null) out.put(id, text);
        }
        return out;
    }

    private static String cstr(byte[] d, int at, int max) {
        int n = 0;
        while (n < max && d[at + n] != 0) n++;
        return new String(d, at, n, StandardCharsets.US_ASCII);
    }
}
