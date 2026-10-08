package org.tielang.zd;

/**
 * base-48 编码（std base48 字符集：{@code 0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL}，
 * '0'=0 … 'L'=47），大数除法取整字符集。与 std/tsha1 的输出编码、zd 头版本数字共用此集。
 * <p>
 * base-48 encoding (the std base48 alphabet {@code 0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL},
 * '0'=0 … 'L'=47), big-integer division over the integer alphabet. Shared by the
 * std/tsha1 output encoding and the zd header version digits.
 */
public final class Base48 {

    /** std base48 字符集（前 48 个字符有效）。 / The std base48 alphabet (first 48 chars are valid). */
    public static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private Base48() {
    }

    /**
     * 十六进制串（大端位模式）→ base-48 最小表示（无前导零；全零返回 "0"）。
     * Hex string (big-endian bit pattern) → the minimal base-48 form (no leading zeros;
     * all-zero returns "0").
     */
    public static String encode(String hex) {
        if (hex == null || hex.isEmpty()) {
            return "";
        }
        java.math.BigInteger num = new java.math.BigInteger(hex, 16);
        if (num.signum() == 0) {
            return "0";
        }
        java.math.BigInteger b48 = java.math.BigInteger.valueOf(48);
        StringBuilder out = new StringBuilder();
        while (num.signum() > 0) {
            java.math.BigInteger[] qr = num.divideAndRemainder(b48);
            out.append(ALPHABET.charAt(qr[1].intValue()));
            num = qr[0];
        }
        return out.reverse().toString();
    }
}
