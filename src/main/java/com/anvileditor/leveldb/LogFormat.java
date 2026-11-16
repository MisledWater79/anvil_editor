package com.anvileditor.leveldb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * LevelDB's record log format, used by both the write-ahead logs (*.log)
 * and the MANIFEST file.
 *
 * The file is split into 32 KiB blocks. Each record is stored as one or
 * more fragments; a fragment has a 7-byte header (masked CRC-32C, 16-bit
 * length, type) and never crosses a block boundary. A block tail shorter
 * than a header is zero-filled.
 */
final class LogFormat {

    static final int BLOCK_SIZE = 32768;
    static final int HEADER_SIZE = 7;

    // Fragment types
    private static final int ZERO = 0;   // preallocated/padding space
    private static final int FULL = 1;
    private static final int FIRST = 2;
    private static final int MIDDLE = 3;
    private static final int LAST = 4;

    private LogFormat() {
    }

    /**
     * Reads every intact record from a log file's bytes.
     *
     * Like LevelDB itself, damaged data is skipped rather than fatal: a
     * fragment with a bad checksum drops the rest of its block, and a
     * record cut short at the end of the file (a crash mid-write) is
     * ignored. Only complete, checksum-valid records are returned.
     */
    static List<byte[]> readRecords(byte[] file) {
        List<byte[]> records = new ArrayList<>();
        ByteArrayOutputStream pending = null;

        int blockStart = 0;
        while (blockStart < file.length) {
            int blockEnd = Math.min(blockStart + BLOCK_SIZE, file.length);
            int pos = blockStart;

            while (blockEnd - pos >= HEADER_SIZE) {
                ByteInput header = new ByteInput(file, pos, HEADER_SIZE);
                int storedCrc = header.readFixed32();
                int length = header.readUnsignedByte() | header.readUnsignedByte() << 8;
                int type = header.readUnsignedByte();

                if (type == ZERO && length == 0) {
                    // Zero-filled space: nothing more in this block
                    break;
                }

                int dataStart = pos + HEADER_SIZE;
                if (dataStart + length > blockEnd) {
                    // Truncated fragment (end of file) or corrupt length: drop it
                    pending = null;
                    break;
                }

                int actualCrc = Crc32c.value(type, file, dataStart, length);
                if (Crc32c.unmask(storedCrc) != actualCrc) {
                    pending = null;
                    break;
                }

                switch (type) {
                    case FULL -> {
                        pending = null;
                        records.add(java.util.Arrays.copyOfRange(file, dataStart, dataStart + length));
                    }
                    case FIRST -> {
                        pending = new ByteArrayOutputStream();
                        pending.write(file, dataStart, length);
                    }
                    case MIDDLE -> {
                        if (pending != null) {
                            pending.write(file, dataStart, length);
                        }
                    }
                    case LAST -> {
                        if (pending != null) {
                            pending.write(file, dataStart, length);
                            records.add(pending.toByteArray());
                            pending = null;
                        }
                    }
                    default -> pending = null; // unknown type: skip the fragment
                }

                pos = dataStart + length;
            }

            blockStart += BLOCK_SIZE;
        }

        return records;
    }

    /**
     * Writes records in log format to a stream that starts at the
     * beginning of a new (empty) file.
     */
    static final class Writer {

        private final OutputStream out;
        private int blockOffset;

        Writer(OutputStream out) {
            this.out = out;
        }

        void addRecord(byte[] record) throws IOException {
            int offset = 0;
            int left = record.length;
            boolean begin = true;

            // An empty record still produces one zero-length FULL fragment
            do {
                int leftover = BLOCK_SIZE - blockOffset;
                if (leftover < HEADER_SIZE) {
                    // Pad the block tail with zeros and start a new block
                    out.write(new byte[leftover]);
                    blockOffset = 0;
                    leftover = BLOCK_SIZE;
                }

                int available = leftover - HEADER_SIZE;
                int fragmentLength = Math.min(left, available);
                boolean end = fragmentLength == left;

                int type;
                if (begin && end) {
                    type = FULL;
                } else if (begin) {
                    type = FIRST;
                } else if (end) {
                    type = LAST;
                } else {
                    type = MIDDLE;
                }

                writeFragment(type, record, offset, fragmentLength);
                offset += fragmentLength;
                left -= fragmentLength;
                begin = false;
            } while (left > 0);
        }

        private void writeFragment(int type, byte[] data, int offset, int length) throws IOException {
            ByteOutput header = new ByteOutput();
            header.writeFixed32(Crc32c.mask(Crc32c.value(type, data, offset, length)));
            header.writeByte(length & 0xFF);
            header.writeByte((length >>> 8) & 0xFF);
            header.writeByte(type);

            out.write(header.toByteArray());
            out.write(data, offset, length);
            blockOffset += HEADER_SIZE + length;
        }
    }
}
