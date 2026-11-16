package com.anvileditor.leveldb;

import java.util.Arrays;
import java.util.HexFormat;

/**
 * An immutable LevelDB key.
 *
 * Wraps a byte array so it can be used in hash maps and sorted collections.
 * Ordering is unsigned byte-by-byte, which is LevelDB's default
 * "leveldb.BytewiseComparator" (the one Bedrock uses).
 */
public final class ByteKey implements Comparable<ByteKey> {

    private final byte[] bytes;
    private final int hash;

    private ByteKey(byte[] bytes) {
        this.bytes = bytes;
        this.hash = Arrays.hashCode(bytes);
    }

    /** Wraps a copy of the given bytes */
    public static ByteKey of(byte[] bytes) {
        return new ByteKey(bytes.clone());
    }

    /** Wraps a copy of part of an array */
    public static ByteKey of(byte[] bytes, int offset, int length) {
        return new ByteKey(Arrays.copyOfRange(bytes, offset, offset + length));
    }

    /** Wraps a string key's ASCII/UTF-8 bytes (e.g. "~local_player") */
    public static ByteKey ofString(String key) {
        return new ByteKey(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Returns a copy of the key bytes */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    public int length() {
        return bytes.length;
    }

    public byte get(int index) {
        return bytes[index];
    }

    /** True if this key begins with the given bytes */
    public boolean startsWith(byte[] prefix) {
        return bytes.length >= prefix.length
                && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    /** Hex form, e.g. "0a000000ff0000002f", for display and error messages */
    public String toHex() {
        return HexFormat.of().formatHex(bytes);
    }

    @Override
    public int compareTo(ByteKey other) {
        return Arrays.compareUnsigned(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ByteKey other && hash == other.hash && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return toHex();
    }
}
