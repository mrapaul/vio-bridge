package vio.bridge;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Deflater;

/**
 * Encodes a bitmap as an 8-bit palette PNG, the only image format the VIO
 * accepts. Android's own PNG encoder only writes RGB(A), so the bitmap is
 * mapped onto a fixed 255-colour palette: a 6x6x6 colour cube plus a
 * 39-step grey ramp for smooth anti-aliased text.
 */
public final class IndexedPng {
    private static final int CUBE = 216, GREYS = 39;
    private static final byte[] PALETTE = new byte[(CUBE + GREYS) * 3];

    static {
        int i = 0;
        for (int r = 0; r < 6; r++)
            for (int g = 0; g < 6; g++)
                for (int b = 0; b < 6; b++) {
                    PALETTE[i++] = (byte) (r * 51);
                    PALETTE[i++] = (byte) (g * 51);
                    PALETTE[i++] = (byte) (b * 51);
                }
        for (int k = 0; k < GREYS; k++) {
            int v = Math.round(k * 255f / (GREYS - 1));
            PALETTE[i++] = (byte) v;
            PALETTE[i++] = (byte) v;
            PALETTE[i++] = (byte) v;
        }
    }

    private IndexedPng() {}

    private static int index(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 14) {
            return CUBE + Math.round(((r + g + b) / 3f) * (GREYS - 1) / 255f);
        }
        return ((r + 25) / 51) * 36 + ((g + 25) / 51) * 6 + (b + 25) / 51;
    }

    /** Snaps a colour to a 4-level cube (still inside the palette) for smaller files. */
    private static int coarseIndex(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 20) return CUBE + Math.round(((r + g + b) / 3f) / 255f * 6) * (GREYS - 1) / 6;
        return ((r + 42) / 85) * 5 / 3 * 36 + ((g + 42) / 85) * 5 / 3 * 6 + ((b + 42) / 85) * 5 / 3;
    }

    /** Palette PNG under {@code maxBytes} if possible, falling back to fewer colours. */
    public static byte[] encode(Bitmap bmp, int maxBytes) {
        byte[] png = encode(bmp, false);
        return png.length <= maxBytes ? png : encode(bmp, true);
    }

    public static byte[] encode(Bitmap bmp) {
        return encode(bmp, false);
    }

    private static byte[] encode(Bitmap bmp, boolean coarse) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] prev = new byte[w], cur = new byte[w];
        byte[] sub = new byte[w], up = new byte[w];
        byte[] raw = new byte[(w + 1) * h];
        int o = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                cur[x] = (byte) (coarse ? coarseIndex(c) : index(c));
            }
            // Pick the PNG row filter (None / Sub / Up) with the smallest residuals.
            long sNone = 0, sSub = 0, sUp = 0;
            for (int x = 0; x < w; x++) {
                sub[x] = (byte) (cur[x] - (x > 0 ? cur[x - 1] : 0));
                up[x] = (byte) (cur[x] - prev[x]);
                sNone += Math.abs((int) cur[x]);
                sSub += Math.abs((int) sub[x]);
                sUp += Math.abs((int) up[x]);
            }
            if (sUp <= sSub && sUp <= sNone) { raw[o++] = 2; System.arraycopy(up, 0, raw, o, w); }
            else if (sSub <= sNone) { raw[o++] = 1; System.arraycopy(sub, 0, raw, o, w); }
            else { raw[o++] = 0; System.arraycopy(cur, 0, raw, o, w); }
            o += w;
            byte[] t = prev; prev = cur; cur = t;
        }
        try {
            ByteArrayOutputStream z = new ByteArrayOutputStream();
            DeflaterOutputStream dz = new DeflaterOutputStream(z, new Deflater(9));
            dz.write(raw);
            dz.close();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            byte[] ihdr = new byte[13];
            put32(ihdr, 0, w);
            put32(ihdr, 4, h);
            ihdr[8] = 8;  // bit depth
            ihdr[9] = 3;  // colour type: indexed
            chunk(out, "IHDR", ihdr);
            chunk(out, "PLTE", PALETTE);
            chunk(out, "IDAT", z.toByteArray());
            chunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        byte[] len = new byte[4];
        put32(len, 0, data.length);
        out.write(len);
        byte[] t = type.getBytes("US-ASCII");
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        byte[] c = new byte[4];
        put32(c, 0, (int) crc.getValue());
        out.write(c);
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
