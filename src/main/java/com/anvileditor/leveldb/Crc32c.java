package com.anvileditor.leveldb;

import java.util.zip.CRC32C;

/**
 * CRC-32C checksums as LevelDB stores them.
 *
 * LevelDB "masks" stored checksums (rotates and adds a constant) because
 * computing the CRC of data that itself contains CRCs is weak otherwise.
 */
final class Crc32c {

    private static final int MASK_DELTA = 0xa282ead8;

    private Crc32c() {
    }

    /** CRC of a type byte followed by a slice of data (the log and table checksum layout) */
    static int value(int typeByte, byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(typeByte);
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    /** CRC of a slice of data followed by a type byte (the table block trailer layout) */
    static int valueThenType(byte[] data, int offset, int length, int typeByte) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        crc.update(typeByte);
        return (int) crc.getValue();
    }

    static int mask(int crc) {
        return ((crc >>> 15) | (crc << 17)) + MASK_DELTA;
    }

    static int unmask(int masked) {
        int rot = masked - MASK_DELTA;
        return (rot >>> 17) | (rot << 15);
    }
}
