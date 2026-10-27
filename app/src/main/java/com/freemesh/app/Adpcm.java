package com.freemesh.app;

import java.util.Arrays;

/** IMA ADPCM (4 bit). Web tarafındaki codec ile birebir aynı biçim: 3 bayt başlık + nibble'lar. */
public final class Adpcm {
    private static final int[] STEP = {7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4429,4872,5359,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
    private static final int[] IDXT = {-1,-1,-1,-1,2,4,6,8};

    private Adpcm() {}

    private static int clamp16(int v) { return v < -32768 ? -32768 : v > 32767 ? 32767 : v; }

    /** Kodlayıcı durumu kareler arasında korunur. */
    public static final class Encoder {
        int pred = 0, idx = 0;

        public byte[] encode(short[] x) {
            byte[] out = new byte[3 + (x.length + 1) / 2];
            out[0] = (byte) (pred & 0xff);
            out[1] = (byte) ((pred >> 8) & 0xff);
            out[2] = (byte) idx;
            int pos = 3, nib = -1;
            for (short v : x) {
                int step = STEP[idx];
                int diff = v - pred, sign = 0;
                if (diff < 0) { sign = 8; diff = -diff; }
                int code = 0, delta = step >> 3;
                if (diff >= step) { code |= 4; diff -= step; delta += step; }
                if (diff >= (step >> 1)) { code |= 2; diff -= step >> 1; delta += step >> 1; }
                if (diff >= (step >> 2)) { code |= 1; delta += step >> 2; }
                pred = clamp16(sign != 0 ? pred - delta : pred + delta);
                idx = Math.max(0, Math.min(88, idx + IDXT[code & 7]));
                int nb = code | sign;
                if (nib < 0) nib = nb;
                else { out[pos++] = (byte) (nib | (nb << 4)); nib = -1; }
            }
            if (nib >= 0) out[pos++] = (byte) nib;
            return Arrays.copyOf(out, pos);
        }
    }

    /** Bir kareyi 16 bit PCM'e çözer. */
    public static short[] decode(byte[] b) {
        int pred = (short) ((b[0] & 0xff) | ((b[1] & 0xff) << 8));
        int idx = Math.min(88, b[2] & 0xff);
        short[] out = new short[(b.length - 3) * 2];
        int k = 0;
        for (int p = 3; p < b.length; p++) {
            int lo = b[p] & 0x0f, hi = (b[p] & 0xff) >> 4;
            for (int nb : new int[]{lo, hi}) {
                int step = STEP[idx];
                int delta = step >> 3;
                if ((nb & 4) != 0) delta += step;
                if ((nb & 2) != 0) delta += step >> 1;
                if ((nb & 1) != 0) delta += step >> 2;
                pred = clamp16((nb & 8) != 0 ? pred - delta : pred + delta);
                idx = Math.max(0, Math.min(88, idx + IDXT[nb & 7]));
                out[k++] = (short) pred;
            }
        }
        return out;
    }
}
