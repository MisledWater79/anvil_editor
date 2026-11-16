package com.anvileditor.leveldb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read access to one sorted table file (*.ldb, or *.sst in older databases).
 *
 * A table is a sequence of blocks, each followed by a 5-byte trailer
 * (compression type + masked CRC). A 48-byte footer at the end of the
 * file points at the index block, whose entries point at the data blocks.
 * Keys inside a table are LevelDB "internal keys": the user key followed
 * by 8 bytes holding the sequence number and the entry type.
 */
final class Table {

    private static final int FOOTER_SIZE = 48;
    private static final int BLOCK_TRAILER_SIZE = 5;
    private static final long MAGIC = 0xdb4775248b80fb57L;

    /** Location of a block inside the table file (size excludes the trailer) */
    record BlockHandle(long offset, long size) {
        static BlockHandle decode(ByteInput in) {
            return new BlockHandle(in.readVarint64(), in.readVarint64());
        }
    }

    /** Receives each entry of a block */
    interface EntryVisitor {
        /**
         * @param key         Buffer holding the entry's key in its first keyLength bytes (reused; copy if kept)
         * @param block       The decompressed block
         * @param valueOffset Offset of the value inside block
         */
        void visit(byte[] key, int keyLength, byte[] block, int valueOffset, int valueLength);
    }

    private final Path path;
    private final long fileNumber;
    private final List<BlockHandle> dataBlocks;

    private Table(Path path, long fileNumber, List<BlockHandle> dataBlocks) {
        this.path = path;
        this.fileNumber = fileNumber;
        this.dataBlocks = dataBlocks;
    }

    long fileNumber() {
        return fileNumber;
    }

    Path path() {
        return path;
    }

    /** Data block locations in key order */
    List<BlockHandle> dataBlocks() {
        return dataBlocks;
    }

    static Table open(Path path, long fileNumber) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size < FOOTER_SIZE) {
                throw new LevelDbException(path.getFileName() + " is too small to be a table");
            }

            ByteInput footer = new ByteInput(readFully(channel, size - FOOTER_SIZE, FOOTER_SIZE));
            BlockHandle.decode(footer); // metaindex (filters); not needed for reading
            BlockHandle indexHandle = BlockHandle.decode(footer);
            footer.seek(FOOTER_SIZE - 8);
            if (footer.readFixed64() != MAGIC) {
                throw new LevelDbException(path.getFileName() + " is not a LevelDB table (bad magic number)");
            }

            byte[] index = readBlock(channel, path, indexHandle);
            List<BlockHandle> blocks = new ArrayList<>();
            forEachEntry(index, (key, keyLength, block, valueOffset, valueLength) ->
                    blocks.add(BlockHandle.decode(new ByteInput(block, valueOffset, valueLength))));

            return new Table(path, fileNumber, List.copyOf(blocks));
        }
    }

    /** Reads, verifies, and decompresses one block */
    byte[] readBlock(BlockHandle handle) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            return readBlock(channel, path, handle);
        }
    }

    private static byte[] readBlock(FileChannel channel, Path path, BlockHandle handle) throws IOException {
        if (handle.size() < 0 || handle.size() > Integer.MAX_VALUE - BLOCK_TRAILER_SIZE
                || handle.offset() < 0 || handle.offset() + handle.size() + BLOCK_TRAILER_SIZE > channel.size()) {
            throw new LevelDbException("Block handle points outside " + path.getFileName());
        }

        int size = (int) handle.size();
        byte[] raw = readFully(channel, handle.offset(), size + BLOCK_TRAILER_SIZE);
        int type = raw[size] & 0xFF;
        int storedCrc = new ByteInput(raw, size + 1, 4).readFixed32();

        if (Crc32c.unmask(storedCrc) != Crc32c.valueThenType(raw, 0, size, type)) {
            throw new LevelDbException("Checksum mismatch in " + path.getFileName()
                    + " at offset " + handle.offset());
        }

        return Compression.decompress(type, raw, 0, size);
    }

    private static byte[] readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position + buffer.position());
            if (n < 0) {
                throw new LevelDbException("Unexpected end of file");
            }
        }
        return buffer.array();
    }

    /**
     * Walks every entry of a decompressed block. Keys are prefix-compressed
     * (each stores how many bytes it shares with the previous key), and the
     * block ends with a restart-point array that a full scan does not need.
     */
    static void forEachEntry(byte[] block, EntryVisitor visitor) {
        if (block.length < 4) {
            throw new LevelDbException("Block is too small");
        }
        int numRestarts = new ByteInput(block, block.length - 4, 4).readFixed32();
        long restartsOffset = block.length - 4 - 4L * numRestarts;
        if (numRestarts < 0 || restartsOffset < 0) {
            throw new LevelDbException("Block has a bad restart count");
        }

        ByteInput in = new ByteInput(block, 0, (int) restartsOffset);
        byte[] key = new byte[64];
        int keyLength = 0;

        while (in.hasRemaining()) {
            int shared = in.readVarint32();
            int nonShared = in.readVarint32();
            int valueLength = in.readVarint32();

            if (shared > keyLength || nonShared < 0 || valueLength < 0) {
                throw new LevelDbException("Block entry has bad key lengths");
            }

            int newLength = shared + nonShared;
            if (newLength > key.length) {
                key = Arrays.copyOf(key, Math.max(newLength, key.length * 2));
            }
            int keyStart = in.position();
            in.skip(nonShared);
            System.arraycopy(block, keyStart, key, shared, nonShared);
            keyLength = newLength;

            int valueOffset = in.position();
            in.skip(valueLength);
            visitor.visit(key, keyLength, block, valueOffset, valueLength);
        }
    }
}
