package dev.scrapsky.io;

import io.airlift.compress.zstd.ZstdCompressor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes synthetic game files in the real formats, for tests only (no game content). */
public final class FixtureWriters {
    private FixtureWriters() {}

    /** HGPAK v2 laid out exactly as monkeyman192/HGPakTool's _pack_files writes it. */
    public static void hgpak(Path out, Map<String, byte[]> files, boolean compress) throws Exception {
        StringBuilder man = new StringBuilder();
        for (String n : files.keySet()) man.append(n).append("\r\n");
        List<byte[]> datas = new java.util.ArrayList<>();
        datas.add(man.toString().getBytes(StandardCharsets.UTF_8));
        datas.addAll(files.values());
        List<String> names = new java.util.ArrayList<>();
        names.add("manifest");
        names.addAll(files.keySet());
        int fileCount = datas.size();
        long[] starts = new long[fileCount];
        long total = 0;
        for (int i = 0; i < fileCount; i++) { starts[i] = total; total += (datas.get(i).length + 15) & ~15L; }
        int chunkCount = (int) ((total + HgPak.CHUNK - 1) / HgPak.CHUNK);
        long dataOffset = 0x30 + 0x20L * fileCount + (compress ? 8L * chunkCount : 0);
        dataOffset += (0x10 - (dataOffset & 0xF)) & 0xF;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (byte[] d : datas) { stream.write(d); stream.write(new byte[(int) (((d.length + 15) & ~15L) - d.length)]); }
        byte[] plain = stream.toByteArray();

        ByteBuffer head = ByteBuffer.allocate(0x30).order(ByteOrder.LITTLE_ENDIAN);
        head.put("HGPAK".getBytes(StandardCharsets.US_ASCII)).put(new byte[3]);
        head.putLong(2).putLong(fileCount).putLong(chunkCount).put((byte) (compress ? 1 : 0)).put(new byte[7]).putLong(dataOffset);
        ByteBuffer index = ByteBuffer.allocate(0x20 * fileCount).order(ByteOrder.LITTLE_ENDIAN);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        for (int i = 0; i < fileCount; i++) {
            index.put(md5.digest(names.get(i).toLowerCase().getBytes(StandardCharsets.UTF_8)));
            index.putLong(starts[i] + dataOffset).putLong(datas.get(i).length);
        }
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(head.array());
        f.write(index.array());
        if (!compress) {
            f.write(new byte[(int) (dataOffset - f.size())]);
            f.write(plain);
        } else {
            ZstdCompressor z = new ZstdCompressor();
            ByteBuffer sizes = ByteBuffer.allocate(8 * chunkCount).order(ByteOrder.LITTLE_ENDIAN);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (int c = 0; c < chunkCount; c++) {
                int from = c * HgPak.CHUNK, len = Math.min(HgPak.CHUNK, plain.length - from);
                byte[] chunk = new byte[HgPak.CHUNK];
                System.arraycopy(plain, from, chunk, 0, len); // the last chunk is padded to full size
                byte[] comp = new byte[z.maxCompressedLength(HgPak.CHUNK)];
                int n = z.compress(chunk, 0, HgPak.CHUNK, comp, 0, comp.length);
                byte[] stored = n >= HgPak.CHUNK ? chunk : java.util.Arrays.copyOf(comp, n);
                sizes.putLong(stored.length);
                body.write(stored);
                body.write(new byte[(16 - (stored.length & 15)) & 15]);
            }
            f.write(sizes.array());
            f.write(new byte[(int) (dataOffset - f.size())]);
            f.write(body.toByteArray());
        }
        Files.createDirectories(out.getParent());
        Files.write(out, f.toByteArray());
    }

    /** cTkLocalisationTable MBIN with the given entries in one language slot (as libMBIN serialises it). */
    public static byte[] locMbin(Map<String, String> entries, int slot) {
        int n = entries.size();
        int entriesStart = 0x30;
        int strings = entriesStart + n * 0x130;
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        ByteBuffer b = ByteBuffer.allocate(strings).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(0, Mbin.MAGIC);
        b.putShort(8, (short) 3300);
        b.putLong(0x20, entriesStart - 0x20);
        b.putInt(0x28, n);
        b.putInt(0x2C, 0xAAAAAA01);
        int i = 0;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            int at = entriesStart + i * 0x130;
            byte[] id = e.getKey().getBytes(StandardCharsets.US_ASCII);
            b.put(at, id, 0, Math.min(id.length, 0x1F));
            byte[] txt = (e.getValue() + "\0").getBytes(StandardCharsets.UTF_8);
            int field = at + 0x20 + slot * 0x10;
            int strAt = strings + tail.size();
            b.putLong(field, strAt - field);
            b.putInt(field + 8, txt.length);
            b.putInt(field + 12, 0xAAAAAA01);
            tail.writeBytes(txt);
            while (tail.size() % 8 != 0) tail.write(0);
            i++;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(b.array());
        all.writeBytes(tail.toByteArray());
        return all.toByteArray();
    }

    /** Uncompressed 32-bit bottom-left TGA. */
    public static byte[] tga(int w, int h, int argbA, int argbB) {
        ByteBuffer b = ByteBuffer.allocate(18 + w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(2, (byte) 2);
        b.putShort(12, (short) w).putShort(14, (short) h);
        b.put(16, (byte) 32).put(17, (byte) 8);
        int p = 18;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int c = ((x / 8 + y / 8) % 2 == 0) ? argbA : argbB;
            b.put(p++, (byte) c).put(p++, (byte) (c >> 8)).put(p++, (byte) (c >> 16)).put(p++, (byte) (c >>> 24));
        }
        return b.array();
    }

    public static Map<String, byte[]> map(Object... kv) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (byte[]) kv[i + 1]);
        return m;
    }
}
