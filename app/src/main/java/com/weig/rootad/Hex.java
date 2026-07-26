package com.weig.rootad;

final class Hex {
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    /** Lower-case hex, the form GitHub and the rule manifests publish digests in. */
    static String encode(byte[] bytes) {
        char[] value = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int item = bytes[index] & 0xff;
            value[index * 2] = DIGITS[item >>> 4];
            value[index * 2 + 1] = DIGITS[item & 0x0f];
        }
        return new String(value);
    }
}
