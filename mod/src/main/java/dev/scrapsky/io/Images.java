package dev.scrapsky.io;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;

/** Decodes the texture formats Scrap Mechanic ships (TGA, DDS BC1/BC2/BC3, PNG) and writes PNG. */
public final class Images {
    private Images() {}

    /** ARGB pixels, row-major from the top-left. */
    public record Img(int w, int h, int[] argb) {
        public int at(int x, int y) { return argb[y * w + x]; }
    }

    public static Img decode(String name, byte[] d) throws IOException {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".tga")) return tga(d);
        if (n.endsWith(".dds")) return dds(d);
        BufferedImage bi = ImageIO.read(new ByteArrayInputStream(d));
        if (bi == null) throw new IOException("unreadable image " + name);
        int[] px = bi.getRGB(0, 0, bi.getWidth(), bi.getHeight(), null, 0, bi.getWidth());
        return new Img(bi.getWidth(), bi.getHeight(), px);
    }

    public static Img tga(byte[] d) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int idLen = d[0] & 0xff, cmapType = d[1] & 0xff, type = d[2] & 0xff;
        int cmapLen = b.getShort(5) & 0xffff, cmapBits = d[7] & 0xff;
        int w = b.getShort(12) & 0xffff, h = b.getShort(14) & 0xffff, bpp = d[16] & 0xff, desc = d[17] & 0xff;
        if (cmapType != 0 || !(type == 2 || type == 3 || type == 10 || type == 11)) throw new IOException("unsupported TGA type " + type);
        int bytes = bpp / 8;
        if (!(bytes == 1 || bytes == 3 || bytes == 4)) throw new IOException("unsupported TGA depth " + bpp);
        int p = 18 + idLen + cmapLen * ((cmapBits + 7) / 8);
        int[] px = new int[w * h];
        int n = 0;
        boolean rle = type >= 9;
        while (n < w * h) {
            int count = 1;
            boolean packet = false;
            if (rle) {
                int hd = d[p++] & 0xff;
                count = (hd & 0x7f) + 1;
                packet = (hd & 0x80) != 0;
            }
            if (packet) {
                int c = pixel(d, p, bytes);
                p += bytes;
                for (int k = 0; k < count && n < w * h; k++) px[n++] = c;
            } else {
                for (int k = 0; k < count && n < w * h; k++) { px[n++] = pixel(d, p, bytes); p += bytes; }
            }
        }
        if ((desc & 0x20) == 0) { // bottom-left origin: flip
            int[] f = new int[w * h];
            for (int y = 0; y < h; y++) System.arraycopy(px, (h - 1 - y) * w, f, y * w, w);
            px = f;
        }
        return new Img(w, h, px);
    }

    private static int pixel(byte[] d, int p, int bytes) {
        if (bytes == 1) { int g = d[p] & 0xff; return 0xff000000 | g << 16 | g << 8 | g; }
        int bl = d[p] & 0xff, g = d[p + 1] & 0xff, r = d[p + 2] & 0xff, a = bytes == 4 ? d[p + 3] & 0xff : 255;
        return a << 24 | r << 16 | g << 8 | bl;
    }

    public static Img dds(byte[] d) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x20534444) throw new IOException("not a DDS");
        int h = b.getInt(12), w = b.getInt(16);
        int pfFlags = b.getInt(80), fourcc = b.getInt(84), rgbBits = b.getInt(88);
        int data = 128;
        int format; // 1 = BC1, 2 = BC2, 3 = BC3, 0 = RGBA8
        if ((pfFlags & 4) != 0) {
            String cc = new String(d, 84, 4, java.nio.charset.StandardCharsets.US_ASCII);
            switch (cc) {
                case "DXT1" -> format = 1;
                case "DXT2", "DXT3" -> format = 2;
                case "DXT4", "DXT5" -> format = 3;
                case "DX10" -> {
                    int dxgi = b.getInt(128);
                    data = 148;
                    format = switch (dxgi) {
                        case 70, 71, 72 -> 1;
                        case 73, 74, 75 -> 2;
                        case 76, 77, 78 -> 3;
                        case 28, 29 -> 0;
                        default -> throw new IOException("unsupported DXGI format " + dxgi);
                    };
                }
                default -> throw new IOException("unsupported DDS " + cc + " " + fourcc);
            }
        } else if (rgbBits == 32) {
            format = 0;
        } else throw new IOException("unsupported DDS pixel format");
        int[] px = new int[w * h];
        if (format == 0) {
            int rm = b.getInt(92), gm = b.getInt(96), bm = b.getInt(100), am = b.getInt(104);
            if (data == 148) { rm = 0xff; gm = 0xff00; bm = 0xff0000; am = 0xff000000; }
            for (int i = 0; i < w * h; i++) {
                int v = b.getInt(data + i * 4);
                px[i] = chan(v, am, 255) << 24 | chan(v, rm, 0) << 16 | chan(v, gm, 0) << 8 | chan(v, bm, 0);
            }
            return new Img(w, h, px);
        }
        int blockBytes = format == 1 ? 8 : 16;
        int bw = Math.max(1, (w + 3) / 4), bh = Math.max(1, (h + 3) / 4);
        int p = data;
        int[] block = new int[16];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                if (p + blockBytes > d.length) throw new IOException("DDS truncated");
                if (format == 1) colorBlock(b, p, block, true);
                else {
                    colorBlock(b, p + 8, block, false);
                    if (format == 2) {
                        long a = b.getLong(p);
                        for (int k = 0; k < 16; k++) block[k] = (block[k] & 0xffffff) | (int) (((a >>> (4 * k)) & 15) * 17) << 24;
                    } else {
                        int a0 = d[p] & 0xff, a1 = d[p + 1] & 0xff;
                        long bits = 0;
                        for (int k = 0; k < 6; k++) bits |= (long) (d[p + 2 + k] & 0xff) << (8 * k);
                        int[] al = new int[8];
                        al[0] = a0; al[1] = a1;
                        if (a0 > a1) for (int k = 1; k < 7; k++) al[k + 1] = ((7 - k) * a0 + k * a1) / 7;
                        else { for (int k = 1; k < 5; k++) al[k + 1] = ((5 - k) * a0 + k * a1) / 5; al[6] = 0; al[7] = 255; }
                        for (int k = 0; k < 16; k++) block[k] = (block[k] & 0xffffff) | al[(int) ((bits >>> (3 * k)) & 7)] << 24;
                    }
                }
                for (int k = 0; k < 16; k++) {
                    int x = bx * 4 + (k & 3), y = by * 4 + (k >> 2);
                    if (x < w && y < h) px[y * w + x] = block[k];
                }
                p += blockBytes;
            }
        }
        return new Img(w, h, px);
    }

    private static int chan(int v, int mask, int dflt) {
        if (mask == 0) return dflt;
        int shift = Integer.numberOfTrailingZeros(mask);
        int max = mask >>> shift;
        return (int) (((v & mask) >>> shift) * 255L / max);
    }

    private static int rgb565(int c) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        return (r * 255 / 31) << 16 | (g * 255 / 63) << 8 | (b * 255 / 31);
    }

    private static void colorBlock(ByteBuffer b, int p, int[] out, boolean bc1) {
        int c0 = b.getShort(p) & 0xffff, c1 = b.getShort(p + 2) & 0xffff;
        int bits = b.getInt(p + 4);
        int[] c = new int[4];
        c[0] = 0xff000000 | rgb565(c0);
        c[1] = 0xff000000 | rgb565(c1);
        if (c0 > c1 || !bc1) {
            c[2] = 0xff000000 | mix(c[0], c[1], 2, 1, 3);
            c[3] = 0xff000000 | mix(c[0], c[1], 1, 2, 3);
        } else {
            c[2] = 0xff000000 | mix(c[0], c[1], 1, 1, 2);
            c[3] = 0;
        }
        for (int k = 0; k < 16; k++) out[k] = c[(bits >>> (2 * k)) & 3];
    }

    private static int mix(int a, int b, int wa, int wb, int div) {
        int r = (((a >> 16) & 255) * wa + ((b >> 16) & 255) * wb) / div;
        int g = (((a >> 8) & 255) * wa + ((b >> 8) & 255) * wb) / div;
        int bl = ((a & 255) * wa + (b & 255) * wb) / div;
        return r << 16 | g << 8 | bl;
    }

    /** Crops [x0,y0,cw,ch] and box-filters it down to size x size. */
    public static Img cropScale(Img src, int x0, int y0, int cw, int ch, int size) {
        cw = Math.max(1, Math.min(cw, src.w() - x0));
        ch = Math.max(1, Math.min(ch, src.h() - y0));
        int[] out = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int sx0 = x0 + x * cw / size, sx1 = Math.max(sx0 + 1, x0 + (x + 1) * cw / size);
                int sy0 = y0 + y * ch / size, sy1 = Math.max(sy0 + 1, y0 + (y + 1) * ch / size);
                long a = 0, r = 0, g = 0, bl = 0, n = 0;
                for (int sy = sy0; sy < sy1; sy++) for (int sx = sx0; sx < sx1; sx++) {
                    int c = src.at(sx, sy);
                    a += c >>> 24; r += (c >> 16) & 255; g += (c >> 8) & 255; bl += c & 255; n++;
                }
                out[y * size + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (bl / n);
            }
        }
        return new Img(size, size, out);
    }

    /**
     * Applies a Scrap Mechanic paint colour the way its blocks look in game: the diffuse
     * texture's alpha marks the paintable area, which is multiplied by the colour; the
     * result is then brought to the colour's brightness so blocks read as that colour.
     */
    public static Img paint(Img src, int rgb, boolean keepAlpha) {
        float tr = ((rgb >> 16) & 255) / 255f, tg = ((rgb >> 8) & 255) / 255f, tb = (rgb & 255) / 255f;
        int[] out = new int[src.argb().length];
        double sum = 0;
        for (int i = 0; i < out.length; i++) {
            int c = src.argb()[i];
            float a = (c >>> 24) / 255f;
            float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
            r *= 1 - a + a * tr; g *= 1 - a + a * tg; b *= 1 - a + a * tb;
            out[i] = (keepAlpha ? (c & 0xff000000) : 0xff000000) | clamp(r) << 16 | clamp(g) << 8 | clamp(b);
            sum += 0.299 * r + 0.587 * g + 0.114 * b;
        }
        double mean = sum / out.length, target = 0.299 * tr + 0.587 * tg + 0.114 * tb;
        if (mean > 0.01) {
            double k = Math.max(0.6, Math.min(2.5, target / mean));
            for (int i = 0; i < out.length; i++) {
                int c = out[i];
                int r = (int) Math.min(255, ((c >> 16) & 255) * k), g = (int) Math.min(255, ((c >> 8) & 255) * k), b = (int) Math.min(255, (c & 255) * k);
                out[i] = (c & 0xff000000) | r << 16 | g << 8 | b;
            }
        }
        return new Img(src.w(), src.h(), out);
    }

    public static Img darken(Img src, float k) {
        int[] out = new int[src.argb().length];
        for (int i = 0; i < out.length; i++) {
            int c = src.argb()[i];
            out[i] = (c & 0xff000000) | (int) (((c >> 16) & 255) * k) << 16 | (int) (((c >> 8) & 255) * k) << 8 | (int) ((c & 255) * k);
        }
        return new Img(src.w(), src.h(), out);
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    public static byte[] png(Img img) throws IOException {
        BufferedImage bi = new BufferedImage(img.w(), img.h(), BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, img.w(), img.h(), img.argb(), 0, img.w());
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", o);
        return o.toByteArray();
    }
}
