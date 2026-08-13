/*
 * Portions of this software are derived from libsodium.
 * Source: https://github.com/jedisct1/libsodium
 * * Copyright (c) 2013-2024 Frank Denis <j at pureftpd dot org>
 */

package codegeasse.crypto;

import java.util.Arrays;

public class SecretStream {

    // =========================================================================
    // 1. PUBLIC STATE & RESULT WRAPPERS
    // =========================================================================

    public static class State {
        public byte[] k = new byte[32]; // key
        public byte[] nonce = new byte[12]; // nonce
        public byte[] _pad = new byte[8]; // padding
    }

    public static class PullResult {
        public byte[] message;
        public byte tag;

        public PullResult(byte[] message, byte tag) {
            this.message = message;
            this.tag = tag;
        }
    }

    // =========================================================================
    // 2. SECRETSTREAM CORE LOGIC
    // =========================================================================

    public static final int ABYTES = 17; // 1 + 16 (tag + poly1305 MAC)
    public static final int TAG_MESSAGE = 0x00;
    public static final int TAG_PUSH = 0x01;
    public static final int TAG_REKEY = 0x02;
    public static final int TAG_FINAL = TAG_PUSH | TAG_REKEY; // 0x03

    private static final byte[] PAD0 = new byte[16];

    public int initPull(State state, byte[] header, byte[] key) {
        Core.HCaCha20(state.k, header, key, null);
        counterReset(state);
        System.arraycopy(header, 16, state.nonce, 4, 8);

        for (int i = 0; i < state._pad.length; i++) {
            state._pad[i] = 0;
        }
        return 0;
    }

    public PullResult pull(State state, byte[] in, int inlen) {
        return pull(state, in, inlen, null, 0);
    }

    public PullResult pull(State state, byte[] in, int inlen, byte[] ad, int adlen) {
        if (inlen < ABYTES) {
            return null; // message too short
        }

        long mlen = inlen - ABYTES;

        // Initialize Poly1305 state
        Poly1305.State poly1305State = new Poly1305.State();
        byte[] block = new byte[64];
        byte[] slen = new byte[8];
        byte[] mac = new byte[16];

        // Generate the Poly1305 key from ChaCha20
        ChaCha20.streamIETF(block, 64, state.nonce, state.k);
        Poly1305.init(poly1305State, block);
        Arrays.fill(block, (byte) 0); // Zero out block

        // Update Poly1305 with additional data (if any)
        if (ad != null && adlen > 0) {
            Poly1305.update(poly1305State, ad, 0, adlen);
            Poly1305.update(poly1305State, PAD0, 0, (0x10 - adlen) & 0xf);
        }

        // Process the tag byte
        Arrays.fill(block, (byte) 0);
        block[0] = in[0];
        ChaCha20.streamIETFXorIC(block, block, 64, state.nonce, 1, state.k);
        byte tag = block[0];
        block[0] = in[0];
        Poly1305.update(poly1305State, block, 0, 64);

        // Update Poly1305 with ciphertext
        byte[] c = Arrays.copyOfRange(in, 1, in.length);
        Poly1305.update(poly1305State, c, 0, (int) mlen);
        int padLen = (int) ((0x10 - 64 + mlen) & 0xf);
        Poly1305.update(poly1305State, PAD0, 0, padLen);

        // Finalize length encoding
        store64_le(slen, 0, adlen);
        Poly1305.update(poly1305State, slen, 0, 8);
        store64_le(slen, 0, 64 + mlen);
        Poly1305.update(poly1305State, slen, 0, 8);

        // Compute MAC
        Poly1305.finalizeMAC(poly1305State, mac);

        // Verify MAC
        int macStart = 1 + (int) mlen;
        int macEnd = macStart + 16;
        byte[] storedMac = Arrays.copyOfRange(in, macStart, macEnd);
        if (!constantTimeCompare(mac, storedMac)) {
            Arrays.fill(mac, (byte) 0);
            return null; // Authentication failed
        }

        // Decrypt message
        byte[] m = new byte[(int) mlen];
        ChaCha20.streamIETFXorIC(m, c, (int) mlen, state.nonce, 2, state.k);

        // XOR inonce with MAC
        for (int i = 0; i < 8; i++) {
            state.nonce[4 + i] ^= mac[i];
        }

        // Increment counter
        incrementCounter(state);

        // Check if rekey is needed
        if ((tag & TAG_REKEY) != 0 || isCounterZero(state)) {
            rekey(state);
        }

        return new PullResult(m, tag);
    }

    private void counterReset(State state) {
        for (int i = 0; i < 4; i++) {
            state.nonce[i] = 0;
        }
        state.nonce[0] = 1;
    }

    private void rekey(State state) {
        byte[] newKeyAndInonce = new byte[32 + 8]; // key + inonce

        System.arraycopy(state.k, 0, newKeyAndInonce, 0, 32);
        System.arraycopy(state.nonce, 4, newKeyAndInonce, 32, 8);

        ChaCha20.streamIETFXorIC(newKeyAndInonce, newKeyAndInonce, 40, state.nonce, 0, state.k);

        System.arraycopy(newKeyAndInonce, 0, state.k, 0, 32);
        System.arraycopy(newKeyAndInonce, 32, state.nonce, 4, 8);

        counterReset(state);
    }

    private void incrementCounter(State state) {
        int carry = 1;
        for (int i = 0; i < 4; i++) {
            int val = (state.nonce[i] & 0xFF) + carry;
            state.nonce[i] = (byte) val;
            carry = val >> 8;
            if (carry == 0) break;
        }
    }

    private boolean isCounterZero(State state) {
        for (int i = 0; i < 4; i++) {
            if (state.nonce[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean constantTimeCompare(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }

    private void store64_le(byte[] dst, int offset, long w) {
        dst[offset] = (byte) (w & 0xFF);
        dst[offset + 1] = (byte) ((w >>> 8) & 0xFF);
        dst[offset + 2] = (byte) ((w >>> 16) & 0xFF);
        dst[offset + 3] = (byte) ((w >>> 24) & 0xFF);
        dst[offset + 4] = (byte) ((w >>> 32) & 0xFF);
        dst[offset + 5] = (byte) ((w >>> 40) & 0xFF);
        dst[offset + 6] = (byte) ((w >>> 48) & 0xFF);
        dst[offset + 7] = (byte) ((w >>> 56) & 0xFF);
    }

    // =========================================================================
    // 3. UNDERLYING CRYPTO MATH MODULES (Nested Static)
    // =========================================================================

    static class ChaCha20 {
        private static final int ROUNDS = 20;

        private static void chachaBlock(int[] output, int[] input) {
            int x0 = input[0]; int x1 = input[1]; int x2 = input[2]; int x3 = input[3];
            int x4 = input[4]; int x5 = input[5]; int x6 = input[6]; int x7 = input[7];
            int x8 = input[8]; int x9 = input[9]; int x10 = input[10]; int x11 = input[11];
            int x12 = input[12]; int x13 = input[13]; int x14 = input[14]; int x15 = input[15];

            for (int i = 0; i < ROUNDS; i += 2) {
                x0 += x4; x12 = rotl32(x12 ^ x0, 16); x8 += x12; x4 = rotl32(x4 ^ x8, 12);
                x0 += x4; x12 = rotl32(x12 ^ x0, 8); x8 += x12; x4 = rotl32(x4 ^ x8, 7);
                x1 += x5; x13 = rotl32(x13 ^ x1, 16); x9 += x13; x5 = rotl32(x5 ^ x9, 12);
                x1 += x5; x13 = rotl32(x13 ^ x1, 8); x9 += x13; x5 = rotl32(x5 ^ x9, 7);
                x2 += x6; x14 = rotl32(x14 ^ x2, 16); x10 += x14; x6 = rotl32(x6 ^ x10, 12);
                x2 += x6; x14 = rotl32(x14 ^ x2, 8); x10 += x14; x6 = rotl32(x6 ^ x10, 7);
                x3 += x7; x15 = rotl32(x15 ^ x3, 16); x11 += x15; x7 = rotl32(x7 ^ x11, 12);
                x3 += x7; x15 = rotl32(x15 ^ x3, 8); x11 += x15; x7 = rotl32(x7 ^ x11, 7);

                x0 += x5; x15 = rotl32(x15 ^ x0, 16); x10 += x15; x5 = rotl32(x5 ^ x10, 12);
                x0 += x5; x15 = rotl32(x15 ^ x0, 8); x10 += x15; x5 = rotl32(x5 ^ x10, 7);
                x1 += x6; x12 = rotl32(x12 ^ x1, 16); x11 += x12; x6 = rotl32(x6 ^ x11, 12);
                x1 += x6; x12 = rotl32(x12 ^ x1, 8); x11 += x12; x6 = rotl32(x6 ^ x11, 7);
                x2 += x7; x13 = rotl32(x13 ^ x2, 16); x8 += x13; x7 = rotl32(x7 ^ x8, 12);
                x2 += x7; x13 = rotl32(x13 ^ x2, 8); x8 += x13; x7 = rotl32(x7 ^ x8, 7);
                x3 += x4; x14 = rotl32(x14 ^ x3, 16); x9 += x14; x4 = rotl32(x4 ^ x9, 12);
                x3 += x4; x14 = rotl32(x14 ^ x3, 8); x9 += x14; x4 = rotl32(x4 ^ x9, 7);
            }

            output[0] = x0 + input[0]; output[1] = x1 + input[1]; output[2] = x2 + input[2]; output[3] = x3 + input[3];
            output[4] = x4 + input[4]; output[5] = x5 + input[5]; output[6] = x6 + input[6]; output[7] = x7 + input[7];
            output[8] = x8 + input[8]; output[9] = x9 + input[9]; output[10] = x10 + input[10]; output[11] = x11 + input[11];
            output[12] = x12 + input[12]; output[13] = x13 + input[13]; output[14] = x14 + input[14]; output[15] = x15 + input[15];
        }

        public static void streamIETF(byte[] c, int clen, byte[] nonce, byte[] key) {
            int[] input = new int[16];
            int[] output = new int[16];
            byte[] blockBytes = new byte[64];

            input[0] = 0x61707865; input[1] = 0x3320646e; input[2] = 0x79622d32; input[3] = 0x6b206574;
            input[4] = load32_le(key, 0); input[5] = load32_le(key, 4); input[6] = load32_le(key, 8); input[7] = load32_le(key, 12);
            input[8] = load32_le(key, 16); input[9] = load32_le(key, 20); input[10] = load32_le(key, 24); input[11] = load32_le(key, 28);
            input[12] = 0;
            input[13] = load32_le(nonce, 0); input[14] = load32_le(nonce, 4); input[15] = load32_le(nonce, 8);

            int pos = 0;
            while (pos < clen) {
                chachaBlock(output, input);
                for (int i = 0; i < 16; i++) { store32_le(blockBytes, i * 4, output[i]); }
                int remaining = clen - pos;
                int toCopy = Math.min(remaining, 64);
                System.arraycopy(blockBytes, 0, c, pos, toCopy);
                pos += 64;
                input[12]++;
            }
        }

        public static void streamIETFXorIC(byte[] c, byte[] m, int mlen, byte[] nonce, int ic, byte[] key) {
            int[] input = new int[16];
            int[] output = new int[16];
            byte[] blockBytes = new byte[64];

            input[0] = 0x61707865; input[1] = 0x3320646e; input[2] = 0x79622d32; input[3] = 0x6b206574;
            input[4] = load32_le(key, 0); input[5] = load32_le(key, 4); input[6] = load32_le(key, 8); input[7] = load32_le(key, 12);
            input[8] = load32_le(key, 16); input[9] = load32_le(key, 20); input[10] = load32_le(key, 24); input[11] = load32_le(key, 28);
            input[12] = ic;
            input[13] = load32_le(nonce, 0); input[14] = load32_le(nonce, 4); input[15] = load32_le(nonce, 8);

            int pos = 0;
            while (pos < mlen) {
                chachaBlock(output, input);
                for (int i = 0; i < 16; i++) { store32_le(blockBytes, i * 4, output[i]); }
                int remaining = mlen - pos;
                int toProcess = Math.min(remaining, 64);
                for (int i = 0; i < toProcess; i++) {
                    c[pos + i] = (byte) (m[pos + i] ^ blockBytes[i]);
                }
                pos += 64;
                input[12]++;
            }
        }

        private static int load32_le(byte[] src, int offset) {
            return (src[offset] & 0xFF) | ((src[offset + 1] & 0xFF) << 8) | ((src[offset + 2] & 0xFF) << 16) | ((src[offset + 3] & 0xFF) << 24);
        }

        private static void store32_le(byte[] dst, int offset, int w) {
            dst[offset] = (byte) (w & 0xFF); dst[offset + 1] = (byte) ((w >>> 8) & 0xFF);
            dst[offset + 2] = (byte) ((w >>> 16) & 0xFF); dst[offset + 3] = (byte) ((w >>> 24) & 0xFF);
        }

        private static int rotl32(int x, int n) { return (x << n) | (x >>> (32 - n)); }
    }

    static class Core {
        public static void HCaCha20(byte[] out, byte[] in, byte[] k, byte[] c) {
            int i, x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15;

            if (c == null) {
                x0 = 0x61707865; x1 = 0x3320646e; x2 = 0x79622d32; x3 = 0x6b206574;
            } else {
                x0 = load32_le(c, 0); x1 = load32_le(c, 4); x2 = load32_le(c, 8); x3 = load32_le(c, 12);
            }

            x4 = load32_le(k, 0); x5 = load32_le(k, 4); x6 = load32_le(k, 8); x7 = load32_le(k, 12);
            x8 = load32_le(k, 16); x9 = load32_le(k, 20); x10 = load32_le(k, 24); x11 = load32_le(k, 28);
            x12 = load32_le(in, 0); x13 = load32_le(in, 4); x14 = load32_le(in, 8); x15 = load32_le(in, 12);

            for (i = 0; i < 10; i++) {
                int[] result;
                result = quarterround(x0, x4, x8, x12); x0 = result[0]; x4 = result[1]; x8 = result[2]; x12 = result[3];
                result = quarterround(x1, x5, x9, x13); x1 = result[0]; x5 = result[1]; x9 = result[2]; x13 = result[3];
                result = quarterround(x2, x6, x10, x14); x2 = result[0]; x6 = result[1]; x10 = result[2]; x14 = result[3];
                result = quarterround(x3, x7, x11, x15); x3 = result[0]; x7 = result[1]; x11 = result[2]; x15 = result[3];
                result = quarterround(x0, x5, x10, x15); x0 = result[0]; x5 = result[1]; x10 = result[2]; x15 = result[3];
                result = quarterround(x1, x6, x11, x12); x1 = result[0]; x6 = result[1]; x11 = result[2]; x12 = result[3];
                result = quarterround(x2, x7, x8, x13); x2 = result[0]; x7 = result[1]; x8 = result[2]; x13 = result[3];
                result = quarterround(x3, x4, x9, x14); x3 = result[0]; x4 = result[1]; x9 = result[2]; x14 = result[3];
            }

            store32_le(out, 0, x0); store32_le(out, 4, x1); store32_le(out, 8, x2); store32_le(out, 12, x3);
            store32_le(out, 16, x12); store32_le(out, 20, x13); store32_le(out, 24, x14); store32_le(out, 28, x15);
        }

        private static int load32_le(byte[] src, int offset) {
            return (src[offset] & 0xFF) | ((src[offset + 1] & 0xFF) << 8) | ((src[offset + 2] & 0xFF) << 16) | ((src[offset + 3] & 0xFF) << 24);
        }

        private static void store32_le(byte[] dst, int offset, int w) {
            dst[offset] = (byte) (w & 0xFF); dst[offset + 1] = (byte) ((w >>> 8) & 0xFF);
            dst[offset + 2] = (byte) ((w >>> 16) & 0xFF); dst[offset + 3] = (byte) ((w >>> 24) & 0xFF);
        }

        private static int rotl32(int x, int n) { return (x << n) | (x >>> (32 - n)); }

        private static int[] quarterround(int a, int b, int c, int d) {
            a += b; d = rotl32(d ^ a, 16); c += d; b = rotl32(b ^ c, 12);
            a += b; d = rotl32(d ^ a, 8); c += d; b = rotl32(b ^ c, 7);
            return new int[] {a, b, c, d};
        }
    }

    static class Poly1305 {
        public static class State {
            private long r0, r1, r2, r3, r4;
            private long h0, h1, h2, h3, h4;
            private long pad0, pad1, pad2, pad3;
            private byte[] buffer = new byte[16];
            private int leftover = 0;
        }

        public static void init(State state, byte[] key) {
            long t0 = load32_le(key, 0); long t1 = load32_le(key, 4); long t2 = load32_le(key, 8); long t3 = load32_le(key, 12);
            state.r0 = t0 & 0x3ffffff; state.r1 = ((t0 >>> 26) | (t1 << 6)) & 0x3ffff03;
            state.r2 = ((t1 >>> 20) | (t2 << 12)) & 0x3ffc0ff; state.r3 = ((t2 >>> 14) | (t3 << 18)) & 0x3f03fff;
            state.r4 = (t3 >>> 8) & 0x00fffff;
            state.h0 = 0; state.h1 = 0; state.h2 = 0; state.h3 = 0; state.h4 = 0;
            state.pad0 = load32_le(key, 16); state.pad1 = load32_le(key, 20); state.pad2 = load32_le(key, 24); state.pad3 = load32_le(key, 28);
            state.leftover = 0;
        }

        public static void update(State state, byte[] m, int offset, int mlen) {
            int pos = offset; int remaining = mlen;
            if (state.leftover > 0) {
                int want = 16 - state.leftover;
                if (want > remaining) want = remaining;
                System.arraycopy(m, pos, state.buffer, state.leftover, want);
                remaining -= want; pos += want; state.leftover += want;
                if (state.leftover < 16) return;
                blocks(state, state.buffer, 0, 16);
                state.leftover = 0;
            }
            if (remaining >= 16) {
                int want = remaining & ~15;
                blocks(state, m, pos, want);
                pos += want; remaining -= want;
            }
            if (remaining > 0) {
                System.arraycopy(m, pos, state.buffer, 0, remaining);
                state.leftover = remaining;
            }
        }

        public static void finalizeMAC(State state, byte[] mac) {
            if (state.leftover > 0) {
                state.buffer[state.leftover] = 1;
                for (int i = state.leftover + 1; i < 16; i++) state.buffer[i] = 0;
                blocksPartial(state, state.buffer, 0, 16);
            }

            long h0 = state.h0; long h1 = state.h1; long h2 = state.h2; long h3 = state.h3; long h4 = state.h4;
            long c;
            c = h1 >>> 26; h1 &= 0x3ffffff; h2 += c; c = h2 >>> 26; h2 &= 0x3ffffff; h3 += c;
            c = h3 >>> 26; h3 &= 0x3ffffff; h4 += c; c = h4 >>> 26; h4 &= 0x3ffffff; h0 += c * 5;
            c = h0 >>> 26; h0 &= 0x3ffffff; h1 += c;

            long g0 = h0 + 5; c = g0 >>> 26; g0 &= 0x3ffffff;
            long g1 = h1 + c; c = g1 >>> 26; g1 &= 0x3ffffff;
            long g2 = h2 + c; c = g2 >>> 26; g2 &= 0x3ffffff;
            long g3 = h3 + c; c = g3 >>> 26; g3 &= 0x3ffffff;
            long g4 = h4 + c - (1L << 26);

            long mask = (g4 >>> 63) - 1;
            g0 &= mask; g1 &= mask; g2 &= mask; g3 &= mask; g4 &= mask;
            mask = ~mask;
            h0 = (h0 & mask) | g0; h1 = (h1 & mask) | g1; h2 = (h2 & mask) | g2; h3 = (h3 & mask) | g3; h4 = (h4 & mask) | g4;

            h0 = ((h0) | (h1 << 26)) & 0xffffffffL; h1 = ((h1 >>> 6) | (h2 << 20)) & 0xffffffffL;
            h2 = ((h2 >>> 12) | (h3 << 14)) & 0xffffffffL; h3 = ((h3 >>> 18) | (h4 << 8)) & 0xffffffffL;

            long f;
            f = h0 + state.pad0; h0 = f & 0xffffffffL;
            f = h1 + state.pad1 + (f >>> 32); h1 = f & 0xffffffffL;
            f = h2 + state.pad2 + (f >>> 32); h2 = f & 0xffffffffL;
            f = h3 + state.pad3 + (f >>> 32); h3 = f & 0xffffffffL;

            store32_le(mac, 0, (int) h0); store32_le(mac, 4, (int) h1);
            store32_le(mac, 8, (int) h2); store32_le(mac, 12, (int) h3);
        }

        private static void blocks(State state, byte[] m, int offset, int bytes) {
            long hibit = 1L << 24;
            long r0 = state.r0; long r1 = state.r1; long r2 = state.r2; long r3 = state.r3; long r4 = state.r4;
            long h0 = state.h0; long h1 = state.h1; long h2 = state.h2; long h3 = state.h3; long h4 = state.h4;
            long s1 = r1 * 5; long s2 = r2 * 5; long s3 = r3 * 5; long s4 = r4 * 5;

            int pos = offset;
            while (bytes >= 16) {
                long t0 = load32_le(m, pos + 0); long t1 = load32_le(m, pos + 4); long t2 = load32_le(m, pos + 8); long t3 = load32_le(m, pos + 12);
                h0 += t0 & 0x3ffffff; h1 += ((t0 >>> 26) | (t1 << 6)) & 0x3ffffff; h2 += ((t1 >>> 20) | (t2 << 12)) & 0x3ffffff;
                h3 += ((t2 >>> 14) | (t3 << 18)) & 0x3ffffff; h4 += (t3 >>> 8) | hibit;

                long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
                long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
                long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
                long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
                long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

                long c;
                c = d0 >>> 26; h0 = d0 & 0x3ffffff; d1 += c;
                c = d1 >>> 26; h1 = d1 & 0x3ffffff; d2 += c;
                c = d2 >>> 26; h2 = d2 & 0x3ffffff; d3 += c;
                c = d3 >>> 26; h3 = d3 & 0x3ffffff; d4 += c;
                c = d4 >>> 26; h4 = d4 & 0x3ffffff; h0 += c * 5;
                c = h0 >>> 26; h0 &= 0x3ffffff; h1 += c;

                pos += 16; bytes -= 16;
            }
            state.h0 = h0; state.h1 = h1; state.h2 = h2; state.h3 = h3; state.h4 = h4;
        }

        private static void blocksPartial(State state, byte[] m, int offset, int bytes) {
            long r0 = state.r0; long r1 = state.r1; long r2 = state.r2; long r3 = state.r3; long r4 = state.r4;
            long h0 = state.h0; long h1 = state.h1; long h2 = state.h2; long h3 = state.h3; long h4 = state.h4;
            long s1 = r1 * 5; long s2 = r2 * 5; long s3 = r3 * 5; long s4 = r4 * 5;

            long t0 = load32_le(m, offset + 0); long t1 = load32_le(m, offset + 4); long t2 = load32_le(m, offset + 8); long t3 = load32_le(m, offset + 12);
            h0 += t0 & 0x3ffffff; h1 += ((t0 >>> 26) | (t1 << 6)) & 0x3ffffff; h2 += ((t1 >>> 20) | (t2 << 12)) & 0x3ffffff; h3 += ((t2 >>> 14) | (t3 << 18)) & 0x3ffffff; h4 += (t3 >>> 8);

            long d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1;
            long d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2;
            long d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3;
            long d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4;
            long d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0;

            long c;
            c = d0 >>> 26; h0 = d0 & 0x3ffffff; d1 += c;
            c = d1 >>> 26; h1 = d1 & 0x3ffffff; d2 += c;
            c = d2 >>> 26; h2 = d2 & 0x3ffffff; d3 += c;
            c = d3 >>> 26; h3 = d3 & 0x3ffffff; d4 += c;
            c = d4 >>> 26; h4 = d4 & 0x3ffffff; h0 += c * 5;
            c = h0 >>> 26; h0 &= 0x3ffffff; h1 += c;

            state.h0 = h0; state.h1 = h1; state.h2 = h2; state.h3 = h3; state.h4 = h4;
        }

        private static long load32_le(byte[] src, int offset) {
            return (src[offset] & 0xFFL) | ((src[offset + 1] & 0xFFL) << 8) | ((src[offset + 2] & 0xFFL) << 16) | ((src[offset + 3] & 0xFFL) << 24);
        }

        private static void store32_le(byte[] dst, int offset, int w) {
            dst[offset] = (byte) (w & 0xFF); dst[offset + 1] = (byte) ((w >>> 8) & 0xFF);
            dst[offset + 2] = (byte) ((w >>> 16) & 0xFF); dst[offset + 3] = (byte) ((w >>> 24) & 0xFF);
        }
    }
}
