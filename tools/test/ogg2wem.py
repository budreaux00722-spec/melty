#!/usr/bin/env python3
"""Test helper: turn a standard Ogg Vorbis file into a modern Wwise Vorbis .wem
(stripped setup with external packed codebooks, 2-byte packet headers, "modified"
packets) so the Java WemToOgg port can be checked end to end in a container that has
no No Man's Sky files. Usage: ogg2wem.py in.ogg packed_codebooks.bin out.wem"""
import struct, sys

def ilog(v):
    r = 0
    while v: r += 1; v >>= 1
    return r

class BR:
    def __init__(s, data): s.d, s.p = data, 0
    def bit(s):
        b = (s.d[s.p >> 3] >> (s.p & 7)) & 1; s.p += 1; return b
    def read(s, n):
        v = 0
        for i in range(n): v |= s.bit() << i
        return v
class BW:
    def __init__(s): s.bits = []
    def write(s, v, n):
        for i in range(n): s.bits.append((v >> i) & 1)
    def bytes(s):
        out = bytearray((len(s.bits) + 7) // 8)
        for i, b in enumerate(s.bits):
            if b: out[i >> 3] |= 1 << (i & 7)
        return bytes(out)

def ogg_packets(data):
    pkts, cur, p, last_gran = [], b"", 0, 0
    while p < len(data):
        assert data[p:p+4] == b"OggS"
        gran = struct.unpack_from("<q", data, p + 6)[0]
        nseg = data[p + 26]; segs = data[p + 27:p + 27 + nseg]; q = p + 27 + nseg
        for s in segs:
            cur += data[q:q + s]; q += s
            if s < 255: pkts.append(cur); cur = b""
        if gran >= 0: last_gran = gran
        p = q
    return pkts, last_gran

def quantvals(entries, dims):
    bits = ilog(entries); vals = entries >> ((bits - 1) * (dims - 1) // dims)
    while True:
        acc = acc1 = 1
        for _ in range(dims): acc *= vals; acc1 *= vals + 1
        if acc <= entries < acc1: return vals
        vals = vals - 1 if acc > entries else vals + 1

def read_full_codebook(br):
    w = BW(); start = br.p
    sync = br.read(24); assert sync == 0x564342, hex(sync)
    dims = br.read(16); entries = br.read(24)
    ordered = br.read(1)
    if ordered:
        br.read(5); cur = 0
        while cur < entries: cur += br.read(ilog(entries - cur))
    else:
        sparse = br.read(1)
        for _ in range(entries):
            if not sparse or br.read(1): br.read(5)
    lt = br.read(4)
    if lt in (1, 2):
        br.read(32); br.read(32); vl = br.read(4) + 1; br.read(1)
        n = quantvals(entries, dims) if lt == 1 else entries * dims
        for _ in range(n): br.read(vl)
    end = br.p
    return [ (br.d[i >> 3] >> (i & 7)) & 1 for i in range(start, end) ]

def library(path):
    f = open(path, "rb").read()
    off = struct.unpack_from("<I", f, len(f) - 4)[0]
    cnt = (len(f) - off) // 4
    offs = [struct.unpack_from("<I", f, off + 4 * i)[0] for i in range(cnt)]
    books = {}
    for i in range(cnt - 1):
        br = BR(f[offs[i]:offs[i + 1]] + b"\0\0\0\0")
        w = BW()
        dims = br.read(4); entries = br.read(14)
        w.write(0x564342, 24); w.write(dims, 16); w.write(entries, 24)
        ordered = br.read(1); w.write(ordered, 1)
        if ordered:
            il = br.read(5); w.write(il, 5); cur = 0
            while cur < entries:
                n = ilog(entries - cur); v = br.read(n); w.write(v, n); cur += v
        else:
            cll = br.read(3); sparse = br.read(1)
            if cll == 0 or cll > 5: continue
            w.write(sparse, 1)
            for _ in range(entries):
                present = 1
                if sparse: present = br.read(1); w.write(present, 1)
                if present: w.write(br.read(cll), 5)
        lt = br.read(1); w.write(lt, 4)
        if lt == 1:
            for n in (32, 32):
                w.write(br.read(n), n)
            vl = br.read(4); w.write(vl, 4); w.write(br.read(1), 1)
            for _ in range(quantvals(entries, dims)): w.write(br.read(vl + 1), vl + 1)
        books.setdefault(tuple(w.bits), i)
    return books

def main(src, lib, dst):
    pkts, total = ogg_packets(open(src, "rb").read())
    ident, setup, audio = pkts[0], pkts[2], pkts[3:]
    ch = ident[11]; rate = struct.unpack_from("<I", ident, 12)[0]; nominal = struct.unpack_from("<i", ident, 20)[0]
    bs = ident[28]; bs0, bs1 = bs & 15, bs >> 4
    books = library(lib)
    br = BR(setup + b"\0\0\0\0"); br.p = 7 * 8
    w = BW()
    cbc = br.read(8) + 1; w.write(cbc - 1, 8)
    for i in range(cbc):
        bits = tuple(read_full_codebook(br))
        if bits not in books: raise SystemExit(f"codebook {i} not in library")
        w.write(books[bits], 10)
    tc = br.read(6) + 1
    for _ in range(tc): br.read(16)
    fc = br.read(6) + 1; w.write(fc - 1, 6)
    def cp(n): v = br.read(n); w.write(v, n); return v
    for _ in range(fc):
        assert br.read(16) == 1
        parts = cp(5); cl = [cp(4) for _ in range(parts)]; mx = max(cl) if cl else -1
        dims = []
        for _ in range(mx + 1):
            dims.append(cp(3) + 1); sub = cp(2)
            if sub: cp(8)
            for _ in range(1 << sub): cp(8)
        cp(2); rb = cp(4)
        for c in cl:
            for _ in range(dims[c]): cp(rb)
    rc = br.read(6) + 1; w.write(rc - 1, 6)
    for _ in range(rc):
        t = br.read(16); w.write(t, 2)
        cp(24); cp(24); cp(24); ncl = cp(6) + 1; cp(8)
        casc = []
        for _ in range(ncl):
            lo = cp(3); hi = cp(5) if cp(1) else 0; casc.append(hi * 8 + lo)
        for c in casc:
            for k in range(8):
                if c & (1 << k): cp(8)
    mc = br.read(6) + 1; w.write(mc - 1, 6)
    for _ in range(mc):
        assert br.read(16) == 0
        sm = cp(4) + 1 if cp(1) else 1
        if cp(1):
            steps = cp(8) + 1
            for _ in range(steps): cp(ilog(ch - 1)); cp(ilog(ch - 1))
        assert cp(2) == 0
        if sm > 1:
            for _ in range(ch): cp(4)
        for _ in range(sm): cp(8); cp(8); cp(8)
    modes = br.read(6) + 1; w.write(modes - 1, 6)
    flags = []
    for _ in range(modes):
        f = cp(1); flags.append(f); br.read(16); br.read(16); cp(8)
    mb = ilog(modes - 1)
    setup_w = w.bytes()
    data = struct.pack("<H", len(setup_w)) + setup_w
    first_audio = len(data)
    for pk in audio:
        r = BR(pk + b"\0"); o = BW()
        assert r.read(1) == 0
        m = r.read(mb); o.write(m, mb)
        if flags[m]: r.read(2)
        while r.p < len(pk) * 8: o.write(r.bit(), 1)
        b = o.bytes()
        data += struct.pack("<H", len(b)) + b
    vorb = struct.pack("<II8xII12xIBB", total, 0xD9, 0, first_audio, 0x12345678, bs0, bs1)
    vorb += b"\0" * (0x2A - len(vorb))
    fmt = struct.pack("<HHIIHHHHI", 0xFFFF, ch, rate, max(nominal, 0) // 8, 0, 0, 0x30, 0, 3 if ch == 2 else 4) + vorb
    assert len(fmt) == 0x42, len(fmt)
    body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(data)) + data
    open(dst, "wb").write(b"RIFF" + struct.pack("<I", len(body)) + body)
    print(f"wrote {dst}: {len(audio)} audio packets, {cbc} codebooks, {total} samples")

if __name__ == "__main__":
    main(*sys.argv[1:4])
