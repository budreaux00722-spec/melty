package dev.scrapsky.io;

import io.airlift.compress.zstd.ZstdDecompressor;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads No Man's Sky 5.50+ ".pak" archives (HGPAK v2) from the player's own install.
 * Layout (from monkeyman192/HGPakTool, MIT): 0x30-byte header, file index of
 * (md5 hash, start offset, decompressed size) records, chunk size table, then data
 * in 64 KiB zstd-compressed chunks on Windows. File 0 is the manifest: the paths of
 * files 1..n separated by CRLF.
 */
public final class HgPak implements AutoCloseable {
    public static final int CHUNK = 0x10000;

    public record Entry(String path, long offset, long size) {}

    private final RandomAccessFile file;
    private final Path path;
    private final boolean compressed;
    private final long dataOffset;
    private final long[] chunkSizes;
    private final long[] chunkOffsets;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final ZstdDecompressor zstd = new ZstdDecompressor();
    private int cachedChunk = -1;
    private byte[] cachedData;

    public HgPak(Path path) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path.toFile(), "r");
        try {
            byte[] head = new byte[0x30];
            file.readFully(head);
            if (!new String(head, 0, 5, StandardCharsets.US_ASCII).equals("HGPAK")) throw new IOException("not an HGPAK file: " + path);
            ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            long version = h.getLong(8);
            if (version != 2) throw new IOException("unsupported HGPAK version " + version);
            int fileCount = (int) h.getLong(16);
            int chunkCount = (int) h.getLong(24);
            compressed = head[32] != 0;
            dataOffset = h.getLong(40);

            byte[] index = new byte[fileCount * 0x20];
            file.readFully(index);
            ByteBuffer ib = ByteBuffer.wrap(index).order(ByteOrder.LITTLE_ENDIAN);
            long[] starts = new long[fileCount];
            long[] sizes = new long[fileCount];
            for (int i = 0; i < fileCount; i++) {
                starts[i] = ib.getLong(i * 0x20 + 16);
                sizes[i] = ib.getLong(i * 0x20 + 24);
            }
            if (compressed) {
                byte[] ct = new byte[chunkCount * 8];
                file.readFully(ct);
                ByteBuffer cb = ByteBuffer.wrap(ct).order(ByteOrder.LITTLE_ENDIAN);
                chunkSizes = new long[chunkCount];
                chunkOffsets = new long[chunkCount];
                long pos = dataOffset;
                for (int i = 0; i < chunkCount; i++) {
                    chunkSizes[i] = cb.getLong(i * 8);
                    chunkOffsets[i] = pos;
                    pos += (chunkSizes[i] + 15) & ~15L;
                }
            } else {
                chunkSizes = new long[0];
                chunkOffsets = new long[0];
            }
            // manifest: offsets are absolute in the compressed form and relative to data start in the stream
            long manifestStart = compressed ? starts[0] - dataOffset : starts[0];
            String manifest = new String(read(manifestStart, sizes[0]), StandardCharsets.UTF_8);
            String[] names = manifest.split("\r\n");
            for (int i = 0; i < names.length && i + 1 < fileCount; i++) {
                String name = names[i].trim();
                if (name.isEmpty()) continue;
                long start = compressed ? starts[i + 1] - dataOffset : starts[i + 1];
                entries.put(name.toLowerCase(java.util.Locale.ROOT), new Entry(name, start, sizes[i + 1]));
            }
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    public Path path() { return path; }

    public Map<String, Entry> entries() { return Collections.unmodifiableMap(entries); }

    public List<Entry> find(java.util.regex.Pattern p) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.values()) if (p.matcher(e.path()).matches()) out.add(e);
        return out;
    }

    public byte[] read(Entry e) throws IOException { return read(e.offset(), e.size()); }

    /** Reads bytes [offset, offset+size): of the decompressed data stream, or of the file when the pak is stored uncompressed. */
    public synchronized byte[] read(long offset, long size) throws IOException {
        if (size > Integer.MAX_VALUE - 16) throw new IOException("entry too large");
        byte[] out = new byte[(int) size];
        if (!compressed) {
            file.seek(offset); // uncompressed paks store absolute file offsets
            file.readFully(out);
            return out;
        }
        int done = 0;
        while (done < size) {
            long pos = offset + done;
            int chunk = (int) (pos / CHUNK);
            int within = (int) (pos % CHUNK);
            byte[] data = chunk(chunk);
            int n = Math.min(data.length - within, (int) size - done);
            if (n <= 0) throw new IOException("short chunk " + chunk + " in " + path);
            System.arraycopy(data, within, out, done, n);
            done += n;
        }
        return out;
    }

    private byte[] chunk(int i) throws IOException {
        if (i == cachedChunk) return cachedData;
        if (i >= chunkSizes.length) throw new IOException("chunk " + i + " out of range in " + path);
        byte[] raw = new byte[(int) chunkSizes[i]];
        file.seek(chunkOffsets[i]);
        file.readFully(raw);
        byte[] data;
        if (raw.length == CHUNK && !isZstdFrame(raw)) {
            data = raw; // stored uncompressed
        } else {
            data = new byte[CHUNK];
            int n;
            try {
                n = zstd.decompress(raw, 0, raw.length, data, 0, CHUNK);
            } catch (RuntimeException ex) {
                if (raw.length == CHUNK) { cachedChunk = i; cachedData = raw; return raw; }
                throw new IOException("zstd chunk " + i + " failed in " + path + ": " + ex.getMessage(), ex);
            }
            if (n < CHUNK) data = java.util.Arrays.copyOf(data, n);
        }
        cachedChunk = i;
        cachedData = data;
        return data;
    }

    private static boolean isZstdFrame(byte[] b) {
        return b.length >= 4 && (b[0] & 0xff) == 0x28 && (b[1] & 0xff) == 0xB5 && (b[2] & 0xff) == 0x2F && (b[3] & 0xff) == 0xFD;
    }

    @Override
    public void close() throws IOException { file.close(); }
}
