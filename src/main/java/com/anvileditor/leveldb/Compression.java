package com.anvileditor.leveldb;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decompresses table blocks.
 *
 * Mojang's LevelDB fork (leveldb-mcpe) adds zlib compressors to the
 * original's "none" and Snappy. Current Bedrock versions write raw
 * deflate (id 4); older worlds may contain zlib (id 2) blocks.
 */
final class Compression {

    static final int NONE = 0;
    static final int SNAPPY = 1;
    static final int ZLIB = 2;
    static final int ZLIB_RAW = 4;

    private Compression() {
    }

    static byte[] decompress(int type, byte[] data, int offset, int length) {
        return switch (type) {
            case NONE -> java.util.Arrays.copyOfRange(data, offset, offset + length);
            case SNAPPY -> snappy(data, offset, length);
            case ZLIB -> inflate(data, offset, length, false);
            case ZLIB_RAW -> inflate(data, offset, length, true);
            default -> throw new LevelDbException("Unsupported block compression type " + type);
        };
    }

    private static byte[] inflate(byte[] data, int offset, int length, boolean raw) {
        Inflater inflater = new Inflater(raw);
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, length * 4));
            byte[] buffer = new byte[16384];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.needsInput()) {
                        // Raw streams written by some encoders end without a final block marker
                        if (raw) {
                            break;
                        }
                        throw new LevelDbException("Compressed block ended early");
                    }
                    if (inflater.needsDictionary()) {
                        throw new LevelDbException("Compressed block needs a preset dictionary");
                    }
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new LevelDbException("Corrupt compressed block: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    /** Decodes the Snappy block format (no framing) */
    static byte[] snappy(byte[] data, int offset, int length) {
        ByteInput in = new ByteInput(data, offset, length);
        long expected = in.readVarint64();
        if (expected < 0 || expected > Integer.MAX_VALUE - 16) {
            throw new LevelDbException("Snappy block is too large");
        }

        byte[] out = new byte[(int) expected];
        int op = 0;

        while (in.hasRemaining()) {
            int tag = in.readUnsignedByte();
            int kind = tag & 3;

            if (kind == 0) {
                int len = tag >>> 2;
                if (len >= 60) {
                    int extraBytes = len - 59;
                    len = 0;
                    for (int i = 0; i < extraBytes; i++) {
                        len |= in.readUnsignedByte() << (8 * i);
                    }
                }
                len += 1;
                if (len < 1 || op + len > out.length) {
                    throw new LevelDbException("Snappy literal overruns the output");
                }
                byte[] literal = in.readBytes(len);
                System.arraycopy(literal, 0, out, op, len);
                op += len;
                continue;
            }

            int len;
            int copyOffset;
            if (kind == 1) {
                len = ((tag >>> 2) & 7) + 4;
                copyOffset = ((tag >>> 5) << 8) | in.readUnsignedByte();
            } else if (kind == 2) {
                len = (tag >>> 2) + 1;
                copyOffset = in.readUnsignedByte() | in.readUnsignedByte() << 8;
            } else {
                len = (tag >>> 2) + 1;
                copyOffset = in.readFixed32();
            }

            if (copyOffset <= 0 || copyOffset > op || op + len > out.length) {
                throw new LevelDbException("Snappy copy is out of range");
            }
            // Byte-by-byte: copies may overlap their own output
            for (int i = 0; i < len; i++) {
                out[op] = out[op - copyOffset];
                op++;
            }
        }

        if (op != out.length) {
            throw new LevelDbException("Snappy block decoded to " + op + " bytes, expected " + out.length);
        }
        return out;
    }
}
