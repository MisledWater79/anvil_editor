package com.anvileditor.leveldb;

import java.io.ByteArrayOutputStream;

/**
 * Growable byte buffer with LevelDB's encodings (the write-side twin of ByteInput).
 */
final class ByteOutput {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    void writeByte(int value) {
        out.write(value);
    }

    void writeFixed32(int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    void writeFixed64(long value) {
        writeFixed32((int) value);
        writeFixed32((int) (value >>> 32));
    }

    void writeVarint64(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    void writeVarint32(int value) {
        writeVarint64(value & 0xFFFFFFFFL);
    }

    void writeBytes(byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    /** Writes a varint32 length followed by the bytes */
    void writeLengthPrefixed(byte[] bytes) {
        writeVarint32(bytes.length);
        writeBytes(bytes);
    }

    int size() {
        return out.size();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }
}
