package org.tielang.zd;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * TSHA1 v2「状态随输出位长」f 模型（快速模型，R_F=12，双轨）的 Java 移植——逐式镜像
 * 唯一语义源 tiec {@code tests/tsha_probe/gen_tsha1_core.py}（同公式、同掩码、同编码、
 * 同初始化顺序；KAT 向量由该文件生成后钉入 {@link ZdProbe}）。输出为 std base48 字符串
 * （{@link Base48}）。非法位长 / 非法 base 返回空串（无 XOF，不足拒绝）。
 * <p>
 * The Java port of the TSHA1 v2 state-per-n f model (the fast model, R_F=12, dual
 * tracks) — a formula-by-formula mirror of the single semantic source tiec
 * {@code tests/tsha_probe/gen_tsha1_core.py} (same formulas, masks, encodings and
 * initialisation order; KAT vectors generated from that file are pinned in
 * {@link ZdProbe}). Output is the std base48 string ({@link Base48}). Illegal bit
 * lengths / bases return the empty string (no XOF, insufficiency refused).
 * <p>
 * 结构（f 路径）：每字 = (M, N) 两个 32 位平面；字宽 W = max(1, ⌈n·L48/32⌉)；轨分配
 * W≥8 双轨、W&lt;8 单轨环式；通用 ring_mix 环式扩散；块压缩为计数器式（纯零填充 +
 * 64 位计数器 t）；压缩后 fin_synth S=4 终筛；末块折回 h[i] ^= v[2i] ^ rrp(v[2i+1],
 * (i*3)&31)。常量 = SEED_F + SHA-256(SEED_F ‖ u64be(k)) 计数器流，IV 预取 32 字 +
 * RCON 16 字。
 * <p>
 * Structure (the f path): each word = (M, N) two 32-bit planes; word width
 * W = max(1, ⌈n·L48/32⌉); track allocation W≥8 dual, W&lt;8 single ring; the generic
 * ring_mix diffusion; counter-style block compression (pure zero padding + the 64-bit
 * counter t); the S=4 fin_synth final sieve; the last block folds back
 * h[i] ^= v[2i] ^ rrp(v[2i+1], (i*3)&31). Constants = SEED_F + the SHA-256 counter
 * stream, IV prefetched 32 words + RCON 16 words.
 */
public final class Tsha1f {

    private static final int M32 = 0xFFFFFFFF;
    private static final double L48 = 5.584962500721156; // log2(48)
    private static final String SEED_F = "TSHA1-2026-f-256-v1";

    /** 合法位长集合（48 进制口径）。 / The legal bit-length set (base-48 digit count). */
    private static final int[] BITS48 = {2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 69, 88, 92, 96, 128, 144};

    private static final int[] IV = new int[32];
    private static final int[] RCON = new int[16];

    static {
        int[] w = stream(SEED_F.getBytes(StandardCharsets.US_ASCII));
        System.arraycopy(w, 0, IV, 0, 32);      // IV 预取 32 字（W 最大 32）
        System.arraycopy(w, 32, RCON, 0, 16);   // RCON 为 IV 之后的 16 字
    }

    private Tsha1f() {
    }

    /** 合法位长判定。 / Whether n is a legal bit length. */
    public static boolean isBits48(int n) {
        for (int v : BITS48) {
            if (v == n) {
                return true;
            }
        }
        return false;
    }

    /** 字宽 W = max(1, ⌈n·L48/32⌉)。 / The word width W = max(1, ⌈n·L48/32⌉). */
    public static int wordsFor(int n) {
        return Math.max(1, (int) Math.ceil(n * L48 / 32.0));
    }

    /**
     * 对外口径：输出 n 个 base48 符号（定长，不足前导 '0' 补足；超长截断前 n 个）。
     * The public form: n base48 symbols (fixed length, left-padded with '0', truncated
     * to the first n when longer).
     */
    public static String of(byte[] msg, int n) {
        if (!isBits48(n)) {
            return "";
        }
        String s = Base48.encode(digest(msg, n));
        if (s.length() < n) {
            return "0".repeat(n - s.length()) + s;
        }
        return s.substring(0, n);
    }

    /** {@link #of(byte[], int)} 的字符串入参便捷形。 / String-input convenience for {@link #of(byte[], int)}. */
    public static String of(String msg, int n) {
        return of(msg.getBytes(StandardCharsets.UTF_8), n);
    }

    /**
     * 状态全宽 hex 摘要（8W 个 hex 字符）。The full-width hex digest (8W hex chars).
     */
    public static String digest(byte[] msg, int n) {
        if (!isBits48(n)) {
            return "";
        }
        int w = wordsFor(n);
        int[] h = new int[w];
        for (int i = 0; i < w; i++) {
            h[i] = IV[i] & M32;
        }
        h[0] = (h[0] ^ 0x01010000 ^ 32) & M32;
        long tLo = 0;
        long tHi = 0;
        int nbytes = msg.length;
        int pos = 0;
        while (pos + 64 < nbytes) {
            byte[] blk = new byte[64];
            System.arraycopy(msg, pos, blk, 0, 64);
            long lo = tLo + 64;
            tHi = (tHi + (lo >> 32)) & M32;
            tLo = lo & M32;
            compress(h, blk, (int) tLo, (int) tHi, false);
            pos += 64;
        }
        int rem = nbytes - pos;
        byte[] blk = new byte[64];
        System.arraycopy(msg, pos, blk, 0, rem);
        long lo = tLo + rem;
        tHi = (tHi + (lo >> 32)) & M32;
        tLo = lo & M32;
        compress(h, blk, (int) tLo, (int) tHi, true);
        StringBuilder sb = new StringBuilder(w * 8);
        for (int i = 0; i < w; i++) {
            sb.append(String.format("%08x", h[i] & M32));
        }
        return sb.toString();
    }

    // ==================== 核心原语 / core primitives ====================

    /** SEED + SHA-256(SEED ‖ u64be(k)) 计数器流 → 48 个 32 位字。 / The counter stream → 48 words. */
    private static int[] stream(byte[] seed) {
        int[] out = new int[48];
        int filled = 0;
        long k = 0;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            while (filled < out.length) {
                md.reset();
                byte[] prefix = new byte[seed.length + 8];
                System.arraycopy(seed, 0, prefix, 0, seed.length);
                for (int i = 0; i < 8; i++) {
                    prefix[seed.length + i] = (byte) (k >> ((7 - i) * 8));
                }
                byte[] d = md.digest(prefix);
                for (int i = 0; i < 32 && filled < out.length; i += 4) {
                    int v = ((d[i] & 0xFF) << 24) | ((d[i + 1] & 0xFF) << 16)
                            | ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                    out[filled++] = v;
                }
                k++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return out;
    }

    /** 32 位循环右移。 / The 32-bit rotate right. */
    private static int rotr(int x, int n) {
        return (x >>> n) | (x << (32 - n));
    }

    /** rrp = rotr(x, r & 31)。 / rrp = rotr(x, r & 31). */
    private static int rrp(int x, int r) {
        return rotr(x, r & 31);
    }

    /** 平衡加（(M,N) 三态位平面）。 / The balanced add over (M,N) three-valued planes. */
    private static long[] tadd2(int ma, int na, int mb, int nb) {
        int aP = ma & (~na);
        int aN = ma & na;
        int bP = mb & (~nb);
        int bN = mb & nb;
        int oPos = ((~ma & mb & ~nb) | (ma & ~na & ~mb) | (aN & bN)) & M32;
        int oNeg = ((~ma & mb & nb) | (ma & na & ~mb) | (aP & bP)) & M32;
        return new long[]{(oPos | oNeg) & M32, oNeg & M32};
    }

    /** 平衡乘。 / The balanced multiply. */
    private static long[] tmul2(int ma, int na, int mb, int nb) {
        int amp = (ma & mb) & M32;
        int no = ((na ^ nb) & amp) & M32;
        return new long[]{amp, no};
    }

    /** 三取二多数。 / The three-way majority. */
    private static int quant3(int m0, int n0, int m1, int n1, int m2, int n2) {
        int a = m0 & (~n0);
        int b = m1 & (~n1);
        int c = m2 & (~n2);
        return ((a & b) | (b & c) | (c & a)) & M32;
    }

    /** 消息预处理：单遍 trit 化（planes + skey 一次扫描）。 / Message preprocessing: one-pass trit planes + skey. */
    private static void blockPlanesSkey(byte[] blk, int[] out) {
        // out[0]=M0, out[1]=N0, out[2]=M1, out[3]=N1, out[4]=skey; Pw 写入 out[5..20]
        int[] pw = new int[16];
        int m0 = 0;
        int n0 = 0;
        int m1 = 0;
        int n1 = 0;
        int skey = 0;
        for (int j = 0; j < 64; j++) {
            int b = blk[j] & 0xFF;
            int tf = (b >> 6) & 3;
            int t = tf - 1;
            if (t != 0) {
                int w = j >> 5;
                int p = j & 31;
                if (t > 0) {
                    if (w == 0) {
                        m0 |= (1 << p);
                    } else {
                        m1 |= (1 << p);
                    }
                } else {
                    if (w == 0) {
                        n0 |= (1 << p);
                    } else {
                        n1 |= (1 << p);
                    }
                }
            }
            int dig = (b & 7) * 3 + (t + 1);
            skey = (skey * 3 + dig) & M32;
            for (int biti = 0; biti < 8; biti++) {
                pw[biti * 2 + (j >> 5)] |= ((b >> biti) & 1) << (j & 31);
            }
        }
        out[0] = m0;
        out[1] = n0;
        out[2] = m1;
        out[3] = n1;
        out[4] = skey;
        System.arraycopy(pw, 0, out, 5, 16);
    }

    /** 位平面吸收（F1-2）：每块一次、compress 轮前调用。 / Bit-plane absorption (F1-2), once per block before the round loop. */
    private static void absorb(int[] lanes, int[] planes) {
        int skey = planes[4];
        for (int w = 0; w < 16; w++) {
            int pw = planes[5 + w];
            if (pw == 0) {
                continue;
            }
            int a = (w * 5 + ((skey >> (w & 7)) & 7)) & 31;
            int an = (w % (lanes.length / 2)); // 锚点字 w%W（<W 时确定叠加）
            int i2 = 2 * an;
            int v = rrp(pw, a);
            int v2 = rrp(pw, a + 17);
            long[] s = tadd2(lanes[i2], lanes[i2 + 1], v, v2);
            lanes[i2] = (int) (s[0] & M32);
            lanes[i2 + 1] = (int) (s[1] & M32);
        }
    }

    /** 通用环式扩散（值语义的唯一定义）。 / The generic ring diffusion (the single definition of value semantics). */
    private static void ringMix(int[] lanes, int[] idx, int r, int skey, int[] rcon,
                                int[] inject, int rconIdx) {
        int l = idx.length;
        if (l == 0) {
            return;
        }
        int rA = (r * 3 + (skey & 7)) & 31;
        int rB = (r * 7 + ((skey >> 3) & 7)) & 31;
        int[] sm = new int[l];
        int[] sn = new int[l];
        for (int k = 0; k < l; k++) {
            int i = idx[k];
            int j2 = idx[(k + 2) % l];
            int j3 = idx[(k + 3) % l];
            int a = (rA + 5 * k) & 31;
            int b = (rB + 7 * k + 3) & 31;
            long[] s1 = tadd2(lanes[2 * i], lanes[2 * i + 1],
                    rrp(lanes[2 * j2], a), rrp(lanes[2 * j2 + 1], a));
            long[] s2 = tadd2((int) s1[0], (int) s1[1],
                    rrp(lanes[2 * j3], b), rrp(lanes[2 * j3 + 1], b));
            sm[k] = (int) s2[0];
            sn[k] = (int) s2[1];
        }
        for (int k = 0; k < l; k++) {
            int i = idx[k];
            lanes[2 * i] = sm[k] & M32;
            lanes[2 * i + 1] = sn[k] & M32;
        }
        for (int k = 0; k < l; k++) {
            long[] p = tmul2(sm[k], sn[k], sm[(k + 2) % l], sn[(k + 2) % l]);
            int i1 = idx[(k + 1) % l];
            lanes[2 * i1] = (lanes[2 * i1] ^ (int) p[0]) & M32;
            lanes[2 * i1 + 1] = (lanes[2 * i1 + 1] ^ (int) p[1]) & M32;
        }
        int maj;
        if (l >= 3) {
            maj = quant3(sm[0], sn[0], sm[1], sn[1], sm[2], sn[2]);
        } else if (l == 2) {
            maj = quant3(sm[0], sn[0], sm[1], sn[1], sm[0], sn[0]);
        } else {
            maj = quant3(sm[0], sn[0], sm[0], sn[0], sm[0], sn[0]);
        }
        int il = idx[l - 1];
        lanes[2 * il] = (lanes[2 * il] ^ maj) & M32;
        lanes[2 * il + 1] = (lanes[2 * il + 1] ^ rrp(maj, (rB + 7) & 31)) & M32;
        if (inject != null) {
            int m0 = inject[0];
            int n0 = inject[1];
            int m1 = inject[2];
            int n1 = inject[3];
            int mA = inject[4];
            int i0 = idx[0];
            long[] s = tadd2(lanes[2 * i0], lanes[2 * i0 + 1], rrp(m0, mA), rrp(n0, mA));
            lanes[2 * i0] = (int) (s[0] & M32);
            lanes[2 * i0 + 1] = (int) (s[1] & M32);
            if (l >= 2) {
                int i1 = idx[1];
                long[] s2 = tadd2(lanes[2 * i1], lanes[2 * i1 + 1],
                        rrp(m1, (mA + 7) & 31), rrp(n1, (mA + 7) & 31));
                lanes[2 * i1] = (int) (s2[0] & M32);
                lanes[2 * i1 + 1] = (int) (s2[1] & M32);
            }
        }
        int i0 = idx[0];
        lanes[2 * i0] = (lanes[2 * i0] ^ rcon[rconIdx & 15]) & M32;
        int il2 = idx[l - 1];
        lanes[2 * il2 + 1] = (lanes[2 * il2 + 1]
                ^ rrp(rcon[(rconIdx + 1) & 15], (r * 5) & 31)) & M32;
    }

    /** S=4 轮全 W 字单环收束（rcon=0 表），随后投影。 / The S=4 full-W single-ring final sieve, then projection. */
    private static void finSynth(int[] h) {
        int w = h.length;
        if (w == 1) {
            return;
        }
        int[] lanes = new int[2 * w];
        for (int i = 0; i < w; i++) {
            lanes[2 * i] = h[i] & M32;
            lanes[2 * i + 1] = rrp(h[(i + 1) % w], 7);
        }
        int[] zero = new int[16];
        int[] idx = new int[w];
        for (int i = 0; i < w; i++) {
            idx[i] = i;
        }
        for (int r = 0; r < 4; r++) {
            ringMix(lanes, idx, r, 0x13579BDF, zero, null, r);
        }
        for (int i = 0; i < w; i++) {
            h[i] = (lanes[2 * i] ^ rrp(lanes[2 * i + 1], (i * 5) & 31)) & M32;
        }
    }

    /** 单块压缩（f 路径）。 / Single-block compression (the f path). */
    private static void compress(int[] h, byte[] blk, int tLo, int tHi, boolean last) {
        int w = h.length;
        int[] planes = new int[21];
        blockPlanesSkey(blk, planes);
        int m0 = planes[0];
        int n0 = planes[1];
        int m1 = planes[2];
        int n1 = planes[3];
        int skey = planes[4];
        int[] lanes = new int[2 * w];
        for (int i = 0; i < w; i++) {
            lanes[2 * i] = h[i] & M32;
            lanes[2 * i + 1] = rrp(h[(i + w / 2) % w], 7);
        }
        for (int i = 0; i < w; i++) {
            lanes[2 * i] = (lanes[2 * i] ^ IV[i % 8]) & M32;
            lanes[2 * i + 1] = (lanes[2 * i + 1] ^ IV[(i + 4) % 8]) & M32;
        }
        lanes[1] = (lanes[1] ^ tLo) & M32;
        int hi = 2 * (w / 2) + 1;
        lanes[hi] = (lanes[hi] ^ tHi) & M32;
        if (last) {
            int lk = 2 * ((w / 2) % w) + 1;
            lanes[lk] = (lanes[lk] ^ M32) & M32;
        }
        absorb(lanes, planes);
        // 轨分配（f）：W≥8 双轨（LA=(W+1)/2），W<8 单轨环式。
        int r12 = 12;
        if (w >= 8) {
            int la = (w + 1) / 2;
            int[] ta = new int[la];
            int[] tb = new int[w - la];
            for (int i = 0; i < la; i++) {
                ta[i] = i;
            }
            for (int i = la; i < w; i++) {
                tb[i - la] = i;
            }
            for (int r = 0; r < r12; r++) {
                int mA = (r * 5 + ((skey >> 6) & 7)) & 31;
                int mB = (r * 11 + ((skey >> 9) & 7)) & 31;
                int mC = (r * 7 + ((skey >> 12) & 7)) & 31;
                int mD = (r * 13 + ((skey >> 15) & 7)) & 31;
                int[] inj0 = {m0, n0, m1, n1, mA};
                int[] inj1 = {m0, n0, m1, n1, mC};
                ringMix(lanes, ta, r, skey, RCON, inj0, r);
                ringMix(lanes, tb, r, skey, RCON, inj1, (r + 8) & 15);
            }
        } else {
            int[] idx = new int[w];
            for (int i = 0; i < w; i++) {
                idx[i] = i;
            }
            for (int r = 0; r < r12; r++) {
                int mA = (r * 5 + ((skey >> 6) & 7)) & 31;
                int mB = (r * 11 + ((skey >> 9) & 7)) & 31;
                int[] inj0 = {m0, n0, m1, n1, mA};
                ringMix(lanes, idx, r, skey, RCON, inj0, r);
            }
        }
        for (int i = 0; i < w; i++) {
            h[i] = (h[i] ^ lanes[2 * i] ^ rrp(lanes[2 * i + 1], (i * 3) & 31)) & M32;
        }
        finSynth(h);
    }
}
