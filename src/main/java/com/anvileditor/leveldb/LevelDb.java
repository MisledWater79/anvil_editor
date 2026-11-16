package com.anvileditor.leveldb;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A LevelDB database as Minecraft: Bedrock Edition stores it (the world's
 * "db" folder), implemented in pure Java.
 *
 * Opening the database builds an in-memory index of every live key: the
 * newest entry per key across all table files and write-ahead logs, with
 * deleted keys removed. Values stay on disk and are decompressed on demand.
 *
 * Writing never rewrites existing files. Changes are appended as a new
 * write-ahead log file, exactly as LevelDB would leave them after a write;
 * the next time Minecraft opens the world, LevelDB replays that log and
 * folds the changes into its tables. Existing data is never modified in
 * place, so a failed save cannot damage what was already there.
 *
 * Writes are refused while another program (Minecraft) holds the
 * database's LOCK file, or if the files changed since the database was opened.
 */
public final class LevelDb {

    /** Receives loading progress (fraction is 0..1) */
    public interface Progress {
        void update(String message, double fraction);

        Progress NONE = (message, fraction) -> { };
    }

    private static final Pattern NUMBERED_FILE = Pattern.compile("(\\d+)\\.(log|ldb|sst)");
    private static final long BLOCK_CACHE_BYTES = 64L * 1024 * 1024;

    /** Where a table-backed value lives: a decompressed block of one table */
    private record BlockRef(Table table, Table.BlockHandle handle) {
    }

    /**
     * The newest known entry for a key. Exactly one of inline/block is used
     * for live values; deleted entries only exist while merging.
     */
    private static final class Entry {
        final long sequence;
        final boolean deleted;
        final byte[] inline;
        final BlockRef block;
        final int valueOffset;
        final int valueLength;

        private Entry(long sequence, boolean deleted, byte[] inline, BlockRef block, int valueOffset, int valueLength) {
            this.sequence = sequence;
            this.deleted = deleted;
            this.inline = inline;
            this.block = block;
            this.valueOffset = valueOffset;
            this.valueLength = valueLength;
        }

        static Entry tombstone(long sequence) {
            return new Entry(sequence, true, null, null, 0, 0);
        }

        static Entry inline(long sequence, byte[] value) {
            return new Entry(sequence, false, value, null, 0, 0);
        }

        static Entry inTable(long sequence, BlockRef block, int offset, int length) {
            return new Entry(sequence, false, null, block, offset, length);
        }

        static Entry newer(Entry a, Entry b) {
            return a.sequence >= b.sequence ? a : b;
        }
    }

    private final Path dir;
    private final Map<ByteKey, Entry> index;
    private final Map<BlockRef, byte[]> blockCache = new LinkedHashMap<>(256, 0.75f, true);
    private long blockCacheBytes;
    private long lastSequence;
    private String fingerprint;

    private LevelDb(Path dir, Map<ByteKey, Entry> index, long lastSequence, String fingerprint) {
        this.dir = dir;
        this.index = index;
        this.lastSequence = lastSequence;
        this.fingerprint = fingerprint;
    }

    // ------------------------------------------------------------------
    // Opening

    public static LevelDb open(Path dir) throws IOException {
        return open(dir, Progress.NONE);
    }

    /**
     * Opens a database folder for reading and builds its key index.
     * Large worlds take a while: every table block is decompressed once.
     */
    public static LevelDb open(Path dir, Progress progress) throws IOException {
        String fingerprint = fingerprint(dir);
        Manifest manifest = Manifest.read(dir);
        Map<ByteKey, Entry> merged = new HashMap<>();
        long lastSequence = manifest.lastSequence;

        // Tables
        progress.update("Reading table index...", 0);
        List<Table> tables = new ArrayList<>();
        for (Manifest.FileRef file : manifest.liveFiles) {
            tables.add(Table.open(tableFile(dir, file.number()), file.number()));
        }

        long totalBlocks = tables.stream().mapToLong(t -> t.dataBlocks().size()).sum();
        long doneBlocks = 0;

        for (Table table : tables) {
            for (Table.BlockHandle handle : table.dataBlocks()) {
                BlockRef ref = new BlockRef(table, handle);
                byte[] block = table.readBlock(handle);

                Table.forEachEntry(block, (key, keyLength, data, valueOffset, valueLength) -> {
                    if (keyLength < 8) {
                        throw new LevelDbException("Table entry key is shorter than its 8-byte tag in "
                                + table.path().getFileName());
                    }
                    int userKeyLength = keyLength - 8;
                    long tag = new ByteInput(key, userKeyLength, 8).readFixed64();
                    long sequence = tag >>> 8;
                    int type = (int) (tag & 0xFF);

                    Entry entry = type == WriteBatch.TYPE_PUT
                            ? Entry.inTable(sequence, ref, valueOffset, valueLength)
                            : Entry.tombstone(sequence);
                    merged.merge(ByteKey.of(key, 0, userKeyLength), entry, Entry::newer);
                });

                doneBlocks++;
                if (doneBlocks % 64 == 0 || doneBlocks == totalBlocks) {
                    progress.update("Reading world data (" + doneBlocks + "/" + totalBlocks + " blocks)...",
                            totalBlocks == 0 ? 1 : (double) doneBlocks / totalBlocks);
                }
            }
        }

        // Write-ahead logs not yet folded into tables, oldest first
        progress.update("Reading recent changes...", 1);
        for (long logNumber : logsToReplay(dir, manifest)) {
            Path logPath = dir.resolve(String.format("%06d.log", logNumber));
            for (byte[] record : LogFormat.readRecords(Files.readAllBytes(logPath))) {
                WriteBatch batch = WriteBatch.decode(record);
                long sequence = batch.sequence();
                for (WriteBatch.Op op : batch.ops()) {
                    Entry entry = op.isDelete() ? Entry.tombstone(sequence) : Entry.inline(sequence, op.value());
                    merged.merge(ByteKey.of(op.key()), entry, Entry::newer);
                    sequence++;
                }
                if (!batch.ops().isEmpty()) {
                    lastSequence = Math.max(lastSequence, batch.lastSequence());
                }
            }
        }

        merged.values().removeIf(e -> e.deleted);
        return new LevelDb(dir, merged, lastSequence, fingerprint);
    }

    private static Path tableFile(Path dir, long number) {
        Path ldb = dir.resolve(String.format("%06d.ldb", number));
        if (Files.isRegularFile(ldb)) {
            return ldb;
        }
        Path sst = dir.resolve(String.format("%06d.sst", number));
        if (Files.isRegularFile(sst)) {
            return sst;
        }
        throw new LevelDbException("Table file " + ldb.getFileName() + " listed in the MANIFEST is missing");
    }

    /** Log files LevelDB would replay on open: number >= log number, or the previous log */
    private static List<Long> logsToReplay(Path dir, Manifest manifest) throws IOException {
        List<Long> logs = new ArrayList<>();
        for (Map.Entry<String, Long> file : numberedFiles(dir).entrySet()) {
            long number = file.getValue();
            if (file.getKey().endsWith(".log")
                    && (number >= manifest.logNumber || number == manifest.prevLogNumber)) {
                logs.add(number);
            }
        }
        logs.sort(Long::compare);
        return logs;
    }

    /** File name -> number for every numbered LevelDB file in the folder */
    private static Map<String, Long> numberedFiles(Path dir) throws IOException {
        Map<String, Long> files = new HashMap<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.forEach(path -> {
                String name = path.getFileName().toString();
                Matcher m = NUMBERED_FILE.matcher(name);
                if (m.matches()) {
                    files.put(name, Long.parseLong(m.group(1)));
                }
            });
        }
        return files;
    }

    // ------------------------------------------------------------------
    // Reading

    public Path directory() {
        return dir;
    }

    /** Number of live keys */
    public synchronized int size() {
        return index.size();
    }

    /** Snapshot of all live keys (unordered) */
    public synchronized List<ByteKey> keys() {
        return new ArrayList<>(index.keySet());
    }

    public synchronized boolean contains(ByteKey key) {
        return index.containsKey(key);
    }

    /** Returns a copy of the value, or null if the key does not exist */
    public synchronized byte[] get(ByteKey key) throws IOException {
        Entry entry = index.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.inline != null) {
            return entry.inline.clone();
        }
        byte[] block = cachedBlock(entry.block);
        return Arrays.copyOfRange(block, entry.valueOffset, entry.valueOffset + entry.valueLength);
    }

    private byte[] cachedBlock(BlockRef ref) throws IOException {
        byte[] block = blockCache.get(ref);
        if (block == null) {
            block = ref.table().readBlock(ref.handle());
            blockCache.put(ref, block);
            blockCacheBytes += block.length;

            var it = blockCache.entrySet().iterator();
            while (blockCacheBytes > BLOCK_CACHE_BYTES && it.hasNext()) {
                var eldest = it.next();
                if (eldest.getKey().equals(ref)) {
                    continue;
                }
                blockCacheBytes -= eldest.getValue().length;
                it.remove();
            }
        }
        return block;
    }

    /** Highest sequence number in use */
    public synchronized long lastSequence() {
        return lastSequence;
    }

    // ------------------------------------------------------------------
    // Writing

    /**
     * True if another program currently has the database open
     * (Minecraft holds the LOCK file while a world is loaded).
     */
    public static boolean isInUse(Path dir) {
        try (FileChannel channel = FileChannel.open(dir.resolve("LOCK"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (IOException | OverlappingFileLockException e) {
            return true;
        }
    }

    /**
     * Applies puts and deletes atomically as a new write-ahead log file.
     *
     * @param changes Key -> new value; a null value deletes the key
     * @throws LevelDbException if the database is in use or changed on disk since it was opened
     */
    public synchronized void write(Map<ByteKey, byte[]> changes) throws IOException {
        if (changes.isEmpty()) {
            return;
        }

        FileChannel lockChannel;
        try {
            lockChannel = FileChannel.open(dir.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileSystemException e) {
            // Windows refuses to open a file another process has locked
            throw new LevelDbException(IN_USE_MESSAGE, e);
        }

        try (lockChannel) {
            FileLock lock;
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException | IOException e) {
                lock = null;
            }
            if (lock == null) {
                throw new LevelDbException(IN_USE_MESSAGE);
            }

            try {
                if (!fingerprint(dir).equals(fingerprint)) {
                    throw new LevelDbException("The world's files changed on disk after it was opened here. "
                            + "Reopen the world before saving so no changes are lost.");
                }
                writeLog(changes);
                fingerprint = fingerprint(dir);
            } finally {
                lock.release();
            }
        }
    }

    private static final String IN_USE_MESSAGE =
            "The world is open in another program (probably Minecraft). Close it there and try again.";

    private void writeLog(Map<ByteKey, byte[]> changes) throws IOException {
        Manifest manifest = Manifest.read(dir);

        long maxNumber = manifest.nextFileNumber;
        for (long number : numberedFiles(dir).values()) {
            maxNumber = Math.max(maxNumber, number);
        }
        long logNumber = maxNumber + 1;

        long firstSequence = lastSequence + 1;
        List<WriteBatch.Op> ops = new ArrayList<>();
        List<ByteKey> keys = new ArrayList<>();
        for (Map.Entry<ByteKey, byte[]> change : changes.entrySet()) {
            byte[] value = change.getValue();
            ops.add(new WriteBatch.Op(change.getKey().toByteArray(), value == null ? null : value.clone()));
            keys.add(change.getKey());
        }
        WriteBatch batch = new WriteBatch(firstSequence, ops);

        // Write to a temporary name first; LevelDB ignores names it does not recognise
        Path finalPath = dir.resolve(String.format("%06d.log", logNumber));
        Path tempPath = dir.resolve(String.format("%06d.log.anviltmp", logNumber));
        try (FileChannel channel = FileChannel.open(tempPath,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             OutputStream out = java.nio.channels.Channels.newOutputStream(channel)) {
            new LogFormat.Writer(out).addRecord(batch.encode());
            out.flush();
            channel.force(true);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
        Files.move(tempPath, finalPath, StandardCopyOption.ATOMIC_MOVE);

        // Mirror the change in memory
        long sequence = firstSequence;
        for (int i = 0; i < ops.size(); i++) {
            WriteBatch.Op op = ops.get(i);
            if (op.isDelete()) {
                index.remove(keys.get(i));
            } else {
                index.put(keys.get(i), Entry.inline(sequence, op.value()));
            }
            sequence++;
        }
        lastSequence = batch.lastSequence();
    }

    /**
     * Summary of the folder's files (name, size, modified time) used to
     * notice changes made by another program between opening and saving.
     */
    private static String fingerprint(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new LevelDbException(dir + " is not a folder");
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> list = Files.list(dir)) {
            for (Path path : list.sorted().toList()) {
                String name = path.getFileName().toString();
                if (name.equals("LOCK")) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                sb.append(name).append(':').append(attrs.size()).append(':')
                        .append(attrs.lastModifiedTime().toMillis()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Keys sharing a prefix (e.g. all "actorprefix" records) */
    public synchronized List<ByteKey> keysWithPrefix(byte[] prefix) {
        List<ByteKey> result = new ArrayList<>();
        for (ByteKey key : index.keySet()) {
            if (key.startsWith(prefix)) {
                result.add(key);
            }
        }
        return result;
    }

    /** Visible for tests: all live keys as an unmodifiable set view copy */
    synchronized Set<ByteKey> keySet() {
        return Set.copyOf(index.keySet());
    }
}
