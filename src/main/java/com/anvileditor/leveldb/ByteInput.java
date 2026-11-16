package com.anvileditor.leveldb;

import java.util.Arrays;

/**
 * Sequential reader over a byte array for LevelDB's encodings:
 * little-endian fixed-width integers, varints, and length-prefixed slices.
 *
 * Every read is bounds-checked and throws LevelDbException rather than
 * returning garbage when the data ends early.
 */
final class ByteInput {

    private final byte[] data;
    private final int limit;
    private int pos;

    ByteInput(byte[] data) {
        this(data, 0, data.length);
    }

    ByteInput(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new LevelDbException("Slice is outside the data");
        }
        this.data = data;
        this.pos = offset;
        this.limit = offset + length;
    }

    int position() {
        return pos;
    }

    void seek(int position) {
        if (position < 0 || position > limit) {
            throw new LevelDbException("Seek outside the data");
        }
        pos = position;
    }

    boolean hasRemaining() {
        return pos < limit;
    }

    int remaining() {
        return limit - pos;
    }

    int readUnsignedByte() {
        require(1);
        return data[pos++] & 0xFF;
    }

    int readFixed32() {
        require(4);
        int value = (data[pos] & 0xFF)
                | (data[pos + 1] & 0xFF) << 8
                | (data[pos + 2] & 0xFF) << 16
                | (data[pos + 3] & 0xFF) << 24;
        pos += 4;
        return value;
    }

    long readFixed64() {
        long low = readFixed32() & 0xFFFFFFFFL;
        long high = readFixed32() & 0xFFFFFFFFL;
        return low | (high << 32);
    }

    int readVarint32() {
        long value = readVarint64();
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new LevelDbException("varint32 is out of range");
        }
        return (int) value;
    }

    long readVarint64() {
        long result = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            int b = readUnsignedByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new LevelDbException("varint is longer than 10 bytes");
    }

    byte[] readBytes(int length) {
        if (length < 0) {
            throw new LevelDbException("Negative length");
        }
        require(length);
        byte[] out = Arrays.copyOfRange(data, pos, pos + length);
        pos += length;
        return out;
    }

    /** Reads a varint32 length followed by that many bytes */
    byte[] readLengthPrefixed() {
        return readBytes(readVarint32());
    }

    void skip(int length) {
        require(length);
        pos += length;
    }

    private void require(int count) {
        if (count > limit - pos) {
            throw new LevelDbException("Unexpected end of data (needed " + count
                    + " bytes, " + (limit - pos) + " left)");
        }
    }
}
