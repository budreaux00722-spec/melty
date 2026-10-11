package dev.scrapsky.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Converts an Audiokinetic Wwise Vorbis ".wem" into a standard Ogg Vorbis stream in memory,
 * so Minecraft can play No Man's Sky's own music from the player's install.
 *
 * A Java port of the modern-file path of ww2ogg by Adam Gashlin (hcs64/ww2ogg, BSD licence,
 * copyright (c) 2002 Xiph.org Foundation, (c) 2009-2016 Adam Gashlin): it rebuilds the Vorbis
 * identification, comment and setup headers that Wwise strips (codebooks come from the bundled
 * packed codebook library) and restores the packet-type/window bits of "modified" packets.
 * Unlike ww2ogg it also writes correct granule positions (what revorb would do).
 */
public final class WemToOgg {
    public static final class ParseException extends IOException {
        public ParseException(String m) { super(m); }
    }

    public record Result(byte[] ogg, int channels, int sampleRate, long samples) {
        public double seconds() { return sampleRate == 0 ? 0 : samples / (double) sampleRate; }
    }

    /** Only reads the RIFF header: channels, rate and sample count (cheap pre-filter). */
    public static Result probe(byte[] wem) throws ParseException {
        Header h = new Header(wem);
        return new Result(null, h.channels, h.sampleRate, h.sampleCount);
    }

    public static Result convert(byte[] wem, CodebookLibrary books) throws ParseException {
        Header h = new Header(wem);
        Ogg os = new Ogg();
        boolean[] modeBlockflag;
        int modeBits;
        // ---- headers ----
        writeIdentification(os, h);
        writeComment(os, h);
        Object[] setup = writeSetup(os, h, wem, books);
        modeBlockflag = (boolean[]) setup[0];
        modeBits = (Integer) setup[1];

        // ---- audio packets ----
        int bs0 = 1 << h.blocksize0Pow, bs1 = 1 << h.blocksize1Pow;
        long granule = 0;
        int prevBlock = 0;
        boolean prevBlockflag = false;
        long offset = h.dataOffset + h.firstAudioPacketOffset;
        long end = h.dataOffset + h.dataSize;
        while (offset < end) {
            int hdr = h.noGranule ? 2 : 6;
            if (offset + hdr > end) throw new ParseException("page header truncated");
            int size = h.u16(offset);
            long payload = offset + hdr;
            long next = payload + size;
            if (next > end) throw new ParseException("page truncated");
            if (size == 0) { offset = next; continue; }

            int mode;
            if (h.modPackets) {
                os.bits(0, 1); // packet type: audio
                Bits ss = new Bits(wem, payload);
                mode = ss.read(modeBits);
                os.bits(mode, modeBits);
                int remainder = ss.read(8 - modeBits);
                if (mode >= modeBlockflag.length) throw new ParseException("bad mode number");
                if (modeBlockflag[mode]) {
                    boolean nextBlockflag = false;
                    if (next + hdr <= end) {
                        int nsize = h.u16(next);
                        if (nsize > 0) {
                            Bits ns = new Bits(wem, next + hdr);
                            int nmode = ns.read(modeBits);
                            if (nmode < modeBlockflag.length) nextBlockflag = modeBlockflag[nmode];
                        }
                    }
                    os.bits(prevBlockflag ? 1 : 0, 1);
                    os.bits(nextBlockflag ? 1 : 0, 1);
                }
                prevBlockflag = modeBlockflag[mode];
                os.bits(remainder, 8 - modeBits);
            } else {
                int first = wem[(int) payload] & 0xff;
                mode = (first >> 1) & ((1 << modeBits) - 1);
                os.bits(first, 8);
            }
            for (long i = payload + 1; i < next; i++) os.bits(wem[(int) i] & 0xff, 8);

            int block = (mode < modeBlockflag.length && modeBlockflag[mode]) ? bs1 : bs0;
            if (prevBlock != 0) granule += prevBlock / 4 + block / 4;
            prevBlock = block;
            offset = next;
            // the last page's granule trims the final block to the true length, as the encoder did
            os.granule = (offset >= end && h.sampleCount > 0) ? Math.min(granule, h.sampleCount) : granule;
            os.flushPage(false, offset >= end);
        }
        return new Result(os.out.toByteArray(), h.channels, h.sampleRate, h.sampleCount);
    }

    // ------------------------------------------------------------------ RIFF

    private static final class Header {
        final byte[] d;
        final boolean le;
        long fmtOffset = -1, vorbOffset = -1, dataOffset = -1, smplOffset = -1;
        long fmtSize = -1, vorbSize = -1, dataSize = -1;
        int channels, sampleRate, avgBytesPerSecond;
        long sampleCount, setupPacketOffset, firstAudioPacketOffset;
        int blocksize0Pow, blocksize1Pow;
        boolean noGranule, modPackets;
        long loopCount, loopStart, loopEnd;

        Header(byte[] d) throws ParseException {
            this.d = d;
            if (d.length < 12) throw new ParseException("too short");
            String riff = new String(d, 0, 4, StandardCharsets.US_ASCII);
            if (riff.equals("RIFF")) le = true;
            else if (riff.equals("RIFX")) le = false;
            else throw new ParseException("missing RIFF");
            long riffSize = u32(4) + 8;
            if (riffSize > d.length) throw new ParseException("RIFF truncated");
            if (!new String(d, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) throw new ParseException("missing WAVE");
            long chunk = 12;
            while (chunk < riffSize) {
                if (chunk + 8 > riffSize) throw new ParseException("chunk header truncated");
                String type = new String(d, (int) chunk, 4, StandardCharsets.US_ASCII);
                long size = u32(chunk + 4);
                switch (type) {
                    case "fmt " -> { fmtOffset = chunk + 8; fmtSize = size; }
                    case "smpl" -> smplOffset = chunk + 8;
                    case "vorb" -> { vorbOffset = chunk + 8; vorbSize = size; }
                    case "data" -> { dataOffset = chunk + 8; dataSize = size; }
                    default -> {}
                }
                chunk = chunk + 8 + size;
            }
            if (fmtOffset < 0 || dataOffset < 0) throw new ParseException("expected fmt, data chunks");
            if (vorbOffset < 0 && fmtSize != 0x42) throw new ParseException("not Wwise Vorbis (fmt size " + fmtSize + ")");
            if (vorbOffset >= 0 && fmtSize != 0x28 && fmtSize != 0x18 && fmtSize != 0x12) throw new ParseException("bad fmt size");
            if (vorbOffset < 0) vorbOffset = fmtOffset + 0x18; // vorb lives inside the 0x42 fmt
            if (u16(fmtOffset) != 0xFFFF) throw new ParseException("not Wwise Vorbis (codec " + Integer.toHexString(u16(fmtOffset)) + ")");
            channels = u16(fmtOffset + 2);
            sampleRate = (int) u32(fmtOffset + 4);
            avgBytesPerSecond = (int) u32(fmtOffset + 8);
            if (smplOffset >= 0) {
                loopCount = u32(smplOffset + 0x1C);
                if (loopCount == 1) { loopStart = u32(smplOffset + 0x2C); loopEnd = u32(smplOffset + 0x30); } else loopCount = 0;
            }
            int vs = (int) vorbSize;
            if (!(vs == -1 || vs == 0x2A || vs == 0x32 || vs == 0x34))
                throw new ParseException("unsupported vorb layout " + Integer.toHexString(vs) + " (old Wwise header triad)");
            sampleCount = u32(vorbOffset);
            long p;
            if (vs == -1 || vs == 0x2A) {
                noGranule = true;
                long modSignal = u32(vorbOffset + 4);
                modPackets = !(modSignal == 0x4A || modSignal == 0x4B || modSignal == 0x69 || modSignal == 0x70);
                p = vorbOffset + 0x10;
            } else {
                p = vorbOffset + 0x18;
            }
            setupPacketOffset = u32(p);
            firstAudioPacketOffset = u32(p + 4);
            p = (vs == 0x32 || vs == 0x34) ? vorbOffset + 0x2C : vorbOffset + 0x24;
            // uid at p, then blocksize exponents
            blocksize0Pow = d[(int) p + 4] & 0xff;
            blocksize1Pow = d[(int) p + 5] & 0xff;
            if (loopCount != 0) {
                loopEnd = loopEnd == 0 ? sampleCount : loopEnd + 1;
                if (loopStart >= sampleCount || loopEnd > sampleCount || loopStart > loopEnd) loopCount = 0;
            }
        }

        int u16(long at) {
            int a = d[(int) at] & 0xff, b = d[(int) at + 1] & 0xff;
            return le ? (a | b << 8) : (a << 8 | b);
        }

        long u32(long at) {
            long a = d[(int) at] & 0xff, b = d[(int) at + 1] & 0xff, c = d[(int) at + 2] & 0xff, e = d[(int) at + 3] & 0xff;
            return le ? (a | b << 8 | c << 16 | e << 24) : (a << 24 | b << 16 | c << 8 | e);
        }
    }

    // ------------------------------------------------------------------ headers

    private static void vorbisHeader(Ogg os, int type) {
        os.bits(type, 8);
        for (byte c : "vorbis".getBytes(StandardCharsets.US_ASCII)) os.bits(c, 8);
    }

    private static void writeIdentification(Ogg os, Header h) {
        vorbisHeader(os, 1);
        os.bits(0, 32);
        os.bits(h.channels, 8);
        os.bits(h.sampleRate, 32);
        os.bits(0, 32);
        os.bits(h.avgBytesPerSecond * 8, 32);
        os.bits(0, 32);
        os.bits(h.blocksize0Pow, 4);
        os.bits(h.blocksize1Pow, 4);
        os.bits(1, 1);
        os.flushPage(false, false);
    }

    private static void writeComment(Ogg os, Header h) {
        vorbisHeader(os, 3);
        byte[] vendor = "converted from Audiokinetic Wwise by Scrap Sky (ww2ogg port)".getBytes(StandardCharsets.US_ASCII);
        os.bits(vendor.length, 32);
        for (byte c : vendor) os.bits(c, 8);
        if (h.loopCount == 0) {
            os.bits(0, 32);
        } else {
            os.bits(2, 32);
            for (String s : new String[]{"LoopStart=" + h.loopStart, "LoopEnd=" + h.loopEnd}) {
                byte[] b = s.getBytes(StandardCharsets.US_ASCII);
                os.bits(b.length, 32);
                for (byte c : b) os.bits(c, 8);
            }
        }
        os.bits(1, 1);
        os.flushPage(false, false);
    }

    private static int ilog(long v) {
        int r = 0;
        while (v != 0) { r++; v >>>= 1; }
        return r;
    }

    private static Object[] writeSetup(Ogg os, Header h, byte[] d, CodebookLibrary books) throws ParseException {
        vorbisHeader(os, 5);
        long packetAt = h.dataOffset + h.setupPacketOffset;
        int size = h.u16(packetAt);
        int hdr = h.noGranule ? 2 : 6;
        if (!h.noGranule && h.u32(packetAt + 2) != 0) throw new ParseException("setup packet granule != 0");
        Bits ss = new Bits(d, packetAt + hdr);

        int codebookCountLess1 = ss.read(8);
        int codebookCount = codebookCountLess1 + 1;
        os.bits(codebookCountLess1, 8);
        for (int i = 0; i < codebookCount; i++) {
            int id = ss.read(10);
            books.rebuild(id, os);
        }
        // time domain transforms placeholder
        os.bits(0, 6);
        os.bits(0, 16);

        int floorCountLess1 = ss.read(6);
        int floorCount = floorCountLess1 + 1;
        os.bits(floorCountLess1, 6);
        for (int i = 0; i < floorCount; i++) {
            os.bits(1, 16); // floor type 1
            int partitions = ss.read(5);
            os.bits(partitions, 5);
            int[] classList = new int[partitions];
            int maxClass = 0;
            for (int j = 0; j < partitions; j++) {
                int c = ss.read(4);
                os.bits(c, 4);
                classList[j] = c;
                maxClass = Math.max(maxClass, c);
            }
            int[] dims = new int[maxClass + 1];
            for (int j = 0; j <= maxClass; j++) {
                int dl1 = ss.read(3);
                os.bits(dl1, 3);
                dims[j] = dl1 + 1;
                int sub = ss.read(2);
                os.bits(sub, 2);
                if (sub != 0) {
                    int master = ss.read(8);
                    os.bits(master, 8);
                    if (master >= codebookCount) throw new ParseException("invalid floor1 masterbook");
                }
                for (int k = 0; k < (1 << sub); k++) {
                    int bp1 = ss.read(8);
                    os.bits(bp1, 8);
                    if (bp1 - 1 >= codebookCount) throw new ParseException("invalid floor1 subclass book");
                }
            }
            int mult = ss.read(2);
            os.bits(mult, 2);
            int rangebits = ss.read(4);
            os.bits(rangebits, 4);
            for (int j = 0; j < partitions; j++) {
                for (int k = 0; k < dims[classList[j]]; k++) {
                    os.bits(ss.read(rangebits), rangebits);
                }
            }
        }

        int residueCountLess1 = ss.read(6);
        int residueCount = residueCountLess1 + 1;
        os.bits(residueCountLess1, 6);
        for (int i = 0; i < residueCount; i++) {
            int type = ss.read(2);
            os.bits(type, 16);
            if (type > 2) throw new ParseException("invalid residue type");
            int begin = ss.read(24), endv = ss.read(24), psize = ss.read(24), classLess1 = ss.read(6), classbook = ss.read(8);
            os.bits(begin, 24); os.bits(endv, 24); os.bits(psize, 24); os.bits(classLess1, 6); os.bits(classbook, 8);
            int classifications = classLess1 + 1;
            if (classbook >= codebookCount) throw new ParseException("invalid residue classbook");
            int[] cascade = new int[classifications];
            for (int j = 0; j < classifications; j++) {
                int high = 0;
                int low = ss.read(3);
                os.bits(low, 3);
                int flag = ss.read(1);
                os.bits(flag, 1);
                if (flag != 0) { high = ss.read(5); os.bits(high, 5); }
                cascade[j] = high * 8 + low;
            }
            for (int j = 0; j < classifications; j++) {
                for (int k = 0; k < 8; k++) {
                    if ((cascade[j] & (1 << k)) != 0) {
                        int book = ss.read(8);
                        os.bits(book, 8);
                        if (book >= codebookCount) throw new ParseException("invalid residue book");
                    }
                }
            }
        }

        int mappingCountLess1 = ss.read(6);
        int mappingCount = mappingCountLess1 + 1;
        os.bits(mappingCountLess1, 6);
        for (int i = 0; i < mappingCount; i++) {
            os.bits(0, 16); // mapping type 0
            int submapsFlag = ss.read(1);
            os.bits(submapsFlag, 1);
            int submaps = 1;
            if (submapsFlag != 0) {
                int sl1 = ss.read(4);
                submaps = sl1 + 1;
                os.bits(sl1, 4);
            }
            int squarePolar = ss.read(1);
            os.bits(squarePolar, 1);
            if (squarePolar != 0) {
                int stepsLess1 = ss.read(8);
                os.bits(stepsLess1, 8);
                int bits = ilog(h.channels - 1);
                for (int j = 0; j <= stepsLess1; j++) {
                    int mag = ss.read(bits), ang = ss.read(bits);
                    os.bits(mag, bits); os.bits(ang, bits);
                    if (ang == mag || mag >= h.channels || ang >= h.channels) throw new ParseException("invalid coupling");
                }
            }
            int reserved = ss.read(2);
            os.bits(reserved, 2);
            if (reserved != 0) throw new ParseException("mapping reserved field nonzero");
            if (submaps > 1) {
                for (int j = 0; j < h.channels; j++) {
                    int mux = ss.read(4);
                    os.bits(mux, 4);
                    if (mux >= submaps) throw new ParseException("mapping_mux >= submaps");
                }
            }
            for (int j = 0; j < submaps; j++) {
                int timeConfig = ss.read(8);
                os.bits(timeConfig, 8);
                int floorNumber = ss.read(8);
                os.bits(floorNumber, 8);
                if (floorNumber >= floorCount) throw new ParseException("invalid floor mapping");
                int residueNumber = ss.read(8);
                os.bits(residueNumber, 8);
                if (residueNumber >= residueCount) throw new ParseException("invalid residue mapping");
            }
        }

        int modeCountLess1 = ss.read(6);
        int modeCount = modeCountLess1 + 1;
        os.bits(modeCountLess1, 6);
        boolean[] blockflag = new boolean[modeCount];
        int modeBits = ilog(modeCount - 1);
        for (int i = 0; i < modeCount; i++) {
            int bf = ss.read(1);
            os.bits(bf, 1);
            blockflag[i] = bf != 0;
            os.bits(0, 16);
            os.bits(0, 16);
            int mapping = ss.read(8);
            os.bits(mapping, 8);
            if (mapping >= mappingCount) throw new ParseException("invalid mode mapping");
        }
        os.bits(1, 1);
        os.flushPage(false, false);

        if ((ss.total + 7) / 8 != size) throw new ParseException("didn't read exactly setup packet");
        if (packetAt + hdr + size != h.dataOffset + h.firstAudioPacketOffset) throw new ParseException("first audio packet doesn't follow setup packet");
        return new Object[]{blockflag, modeBits};
    }

    // ------------------------------------------------------------------ bit I/O

    /** LSB-first bit reader (Vorbis bit order). */
    static final class Bits {
        private final byte[] d;
        private long pos;
        private int buf, left;
        long total;

        Bits(byte[] d, long start) { this.d = d; this.pos = start; }

        int bit() throws ParseException {
            if (left == 0) {
                if (pos >= d.length) throw new ParseException("out of bits");
                buf = d[(int) pos++] & 0xff;
                left = 8;
            }
            total++;
            left--;
            return (buf & (0x80 >> left)) != 0 ? 1 : 0;
        }

        int read(int n) throws ParseException {
            int v = 0;
            for (int i = 0; i < n; i++) if (bit() != 0) v |= 1 << i;
            return v;
        }
    }

    /** Ogg page writer: each flush makes one page holding the bits written since the last flush. */
    static final class Ogg {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        private byte[] payload = new byte[255 * 255];
        private int payloadBytes;
        private int bitBuffer, bitsStored;
        private boolean first = true, continued;
        long granule;
        private int seqno;

        void bit(int b) {
            if (b != 0) bitBuffer |= 1 << bitsStored;
            if (++bitsStored == 8) flushBits();
        }

        void bits(long v, int n) {
            for (int i = 0; i < n; i++) bit((int) ((v >>> i) & 1));
        }

        private void flushBits() {
            if (bitsStored == 0) return;
            if (payloadBytes == payload.length) throw new IllegalStateException("ran out of space in an Ogg packet");
            payload[payloadBytes++] = (byte) bitBuffer;
            bitBuffer = 0;
            bitsStored = 0;
        }

        void flushPage(boolean nextContinued, boolean last) {
            if (payloadBytes != payload.length) flushBits();
            if (payloadBytes == 0) return;
            int segments = (payloadBytes + 255) / 255;
            if (segments == 256) segments = 255;
            byte[] page = new byte[27 + segments + payloadBytes];
            page[0] = 'O'; page[1] = 'g'; page[2] = 'g'; page[3] = 'S';
            page[4] = 0;
            page[5] = (byte) ((continued ? 1 : 0) | (first ? 2 : 0) | (last ? 4 : 0));
            le32(page, 6, (int) granule);
            le32(page, 10, (int) (granule >>> 32));
            le32(page, 14, 1);
            le32(page, 18, seqno);
            le32(page, 22, 0);
            page[26] = (byte) segments;
            int left = payloadBytes;
            for (int i = 0; i < segments; i++) {
                int s = Math.min(255, left);
                page[27 + i] = (byte) s;
                left -= s;
            }
            System.arraycopy(payload, 0, page, 27 + segments, payloadBytes);
            le32(page, 22, OggCrc.checksum(page));
            out.writeBytes(page);
            seqno++;
            first = false;
            continued = nextContinued;
            payloadBytes = 0;
        }

        private static void le32(byte[] b, int at, int v) {
            b[at] = (byte) v; b[at + 1] = (byte) (v >>> 8); b[at + 2] = (byte) (v >>> 16); b[at + 3] = (byte) (v >>> 24);
        }
    }

    static final class OggCrc {
        private static final int[] TABLE = new int[256];
        static {
            for (int i = 0; i < 256; i++) {
                int r = i << 24;
                for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04c11db7 : r << 1;
                TABLE[i] = r;
            }
        }
        static int checksum(byte[] b) {
            int crc = 0;
            for (byte x : b) crc = (crc << 8) ^ TABLE[((crc >>> 24) & 0xff) ^ (x & 0xff)];
            return crc;
        }
    }

    // ------------------------------------------------------------------ codebooks

    /** ww2ogg's packed codebook library (packed_codebooks_aoTuV_603.bin, BSD). */
    public static final class CodebookLibrary {
        private final byte[] data;
        private final int[] offsets;

        public CodebookLibrary(byte[] file) {
            int n = file.length;
            int offsetOffset = (file[n - 4] & 0xff) | (file[n - 3] & 0xff) << 8 | (file[n - 2] & 0xff) << 16 | (file[n - 1] & 0xff) << 24;
            int count = (n - offsetOffset) / 4;
            data = java.util.Arrays.copyOf(file, offsetOffset);
            offsets = new int[count];
            for (int i = 0; i < count; i++) {
                int p = offsetOffset + i * 4;
                offsets[i] = (file[p] & 0xff) | (file[p + 1] & 0xff) << 8 | (file[p + 2] & 0xff) << 16 | (file[p + 3] & 0xff) << 24;
            }
        }

        public static CodebookLibrary load(InputStream in) throws IOException {
            try (in) { return new CodebookLibrary(in.readAllBytes()); }
        }

        void rebuild(int id, Ogg os) throws ParseException {
            if (id < 0 || id >= offsets.length - 1) throw new ParseException("invalid codebook id " + id);
            int start = offsets[id], size = offsets[id + 1] - offsets[id];
            Bits bis = new Bits(data, start);
            int dimensions = bis.read(4);
            int entries = bis.read(14);
            os.bits(0x564342, 24);
            os.bits(dimensions, 16);
            os.bits(entries, 24);
            int ordered = bis.read(1);
            os.bits(ordered, 1);
            if (ordered != 0) {
                int initial = bis.read(5);
                os.bits(initial, 5);
                int current = 0;
                while (current < entries) {
                    int nb = ilog(entries - current);
                    int number = bis.read(nb);
                    os.bits(number, nb);
                    current += number;
                }
                if (current > entries) throw new ParseException("current_entry out of range");
            } else {
                int cll = bis.read(3);
                int sparse = bis.read(1);
                if (cll == 0 || cll > 5) throw new ParseException("nonsense codeword length");
                os.bits(sparse, 1);
                for (int i = 0; i < entries; i++) {
                    boolean present = true;
                    if (sparse != 0) {
                        int p = bis.read(1);
                        os.bits(p, 1);
                        present = p != 0;
                    }
                    if (present) os.bits(bis.read(cll), 5);
                }
            }
            int lookup = bis.read(1);
            os.bits(lookup, 4);
            if (lookup == 1) {
                int min = bis.read(32), max = bis.read(32), valueLength = bis.read(4), seq = bis.read(1);
                os.bits(min & 0xffffffffL, 32); os.bits(max & 0xffffffffL, 32); os.bits(valueLength, 4); os.bits(seq, 1);
                int quantvals = quantvals(entries, dimensions);
                for (int i = 0; i < quantvals; i++) os.bits(bis.read(valueLength + 1), valueLength + 1);
            }
            if (bis.total / 8 + 1 != size) throw new ParseException("codebook size mismatch");
        }

        private static int quantvals(int entries, int dimensions) {
            int bits = ilog(entries);
            int vals = entries >> ((bits - 1) * (dimensions - 1) / dimensions);
            while (true) {
                long acc = 1, acc1 = 1;
                for (int i = 0; i < dimensions; i++) { acc *= vals; acc1 *= vals + 1; }
                if (acc <= entries && acc1 > entries) return vals;
                if (acc > entries) vals--; else vals++;
            }
        }
    }
}
