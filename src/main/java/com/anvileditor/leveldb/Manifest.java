package com.anvileditor.leveldb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The database's current file list, rebuilt from the MANIFEST.
 *
 * The CURRENT file names the active MANIFEST. That MANIFEST is a log of
 * "version edits": each one adds or removes table files and updates
 * counters such as the log number and last sequence number. Replaying
 * all edits gives the set of live tables.
 */
final class Manifest {

    static final String BYTEWISE_COMPARATOR = "leveldb.BytewiseComparator";

    // Version edit field tags
    private static final int COMPARATOR = 1;
    private static final int LOG_NUMBER = 2;
    private static final int NEXT_FILE_NUMBER = 3;
    private static final int LAST_SEQUENCE = 4;
    private static final int COMPACT_POINTER = 5;
    private static final int DELETED_FILE = 6;
    private static final int NEW_FILE = 7;
    private static final int PREV_LOG_NUMBER = 9;

    /** A live table and the LSM level it sits on */
    record FileRef(int level, long number) {
    }

    final String manifestName;
    final Set<FileRef> liveFiles;
    final long logNumber;
    final long prevLogNumber;
    final long nextFileNumber;
    final long lastSequence;

    private Manifest(String manifestName, Set<FileRef> liveFiles, long logNumber,
                     long prevLogNumber, long nextFileNumber, long lastSequence) {
        this.manifestName = manifestName;
        this.liveFiles = liveFiles;
        this.logNumber = logNumber;
        this.prevLogNumber = prevLogNumber;
        this.nextFileNumber = nextFileNumber;
        this.lastSequence = lastSequence;
    }

    static Manifest read(Path dbDir) throws IOException {
        Path current = dbDir.resolve("CURRENT");
        if (!Files.isRegularFile(current)) {
            throw new LevelDbException("No CURRENT file in " + dbDir + "; is this a LevelDB folder?");
        }

        String name = Files.readString(current, StandardCharsets.US_ASCII).trim();
        if (!name.startsWith("MANIFEST-") || name.contains("/") || name.contains("\\")) {
            throw new LevelDbException("CURRENT does not name a MANIFEST file: " + name);
        }

        Path manifestPath = dbDir.resolve(name);
        if (!Files.isRegularFile(manifestPath)) {
            throw new LevelDbException("CURRENT names " + name + " but that file is missing");
        }

        Set<FileRef> files = new LinkedHashSet<>();
        long logNumber = 0;
        long prevLogNumber = 0;
        long nextFileNumber = 0;
        long lastSequence = 0;
        boolean sawLog = false;
        boolean sawNextFile = false;
        boolean sawLastSequence = false;

        for (byte[] record : LogFormat.readRecords(Files.readAllBytes(manifestPath))) {
            ByteInput in = new ByteInput(record);
            while (in.hasRemaining()) {
                int tag = in.readVarint32();
                switch (tag) {
                    case COMPARATOR -> {
                        String comparator = new String(in.readLengthPrefixed(), StandardCharsets.UTF_8);
                        if (!BYTEWISE_COMPARATOR.equals(comparator)) {
                            throw new LevelDbException("Unsupported key comparator: " + comparator);
                        }
                    }
                    case LOG_NUMBER -> {
                        logNumber = in.readVarint64();
                        sawLog = true;
                    }
                    case PREV_LOG_NUMBER -> prevLogNumber = in.readVarint64();
                    case NEXT_FILE_NUMBER -> {
                        nextFileNumber = in.readVarint64();
                        sawNextFile = true;
                    }
                    case LAST_SEQUENCE -> {
                        lastSequence = in.readVarint64();
                        sawLastSequence = true;
                    }
                    case COMPACT_POINTER -> {
                        in.readVarint32();
                        in.readLengthPrefixed();
                    }
                    case DELETED_FILE -> files.remove(new FileRef(in.readVarint32(), in.readVarint64()));
                    case NEW_FILE -> {
                        int level = in.readVarint32();
                        long number = in.readVarint64();
                        in.readVarint64();        // file size
                        in.readLengthPrefixed();  // smallest key
                        in.readLengthPrefixed();  // largest key
                        files.add(new FileRef(level, number));
                    }
                    default -> throw new LevelDbException("Unknown MANIFEST field tag " + tag);
                }
            }
        }

        if (!sawLog || !sawNextFile || !sawLastSequence) {
            throw new LevelDbException(name + " is incomplete (missing log number, next file number or last sequence)");
        }

        return new Manifest(name, files, logNumber, prevLogNumber, nextFileNumber, lastSequence);
    }
}
