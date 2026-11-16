package com.anvileditor.world;

import com.anvileditor.leveldb.ByteKey;
import com.anvileditor.leveldb.LevelDb;
import com.anvileditor.leveldb.LevelDbException;
import com.anvileditor.nbt.BedrockLevelDat;
import com.anvileditor.nbt.NbtWriter;
import com.anvileditor.nbt.Tag;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An opened Minecraft: Bedrock Edition world folder (the one holding
 * level.dat, levelname.txt and the db folder).
 *
 * Edits are kept in memory until save() is called; reading a record
 * returns the pending edit when there is one, so the UI always shows
 * what will be written.
 */
public final class BedrockWorld {

    private final Path folder;
    private final String name;
    private final LevelDb db;

    private BedrockLevelDat levelDat;
    private boolean levelDatChanged;

    private final Map<Dimension, NavigableSet<ChunkPos>> chunks = new EnumMap<>(Dimension.class);
    private final Map<ChunkPos, List<ByteKey>> chunkKeys = new HashMap<>();
    private final List<WorldRecord> otherRecords = new ArrayList<>();

    private final Map<ByteKey, byte[]> pending = new LinkedHashMap<>();

    private BedrockWorld(Path folder, String name, LevelDb db, BedrockLevelDat levelDat) {
        this.folder = folder;
        this.name = name;
        this.db = db;
        this.levelDat = levelDat;
        for (Dimension d : Dimension.values()) {
            chunks.put(d, new TreeSet<>());
        }
    }

    // ------------------------------------------------------------------
    // Opening

    /**
     * Opens a world. Accepts the world folder itself, its db folder, or
     * its level.dat file.
     */
    public static BedrockWorld open(Path path, LevelDb.Progress progress) throws IOException {
        Path folder = resolveWorldFolder(path);
        Path dbDir = folder.resolve("db");

        BedrockLevelDat levelDat = null;
        Path levelDatPath = folder.resolve("level.dat");
        if (Files.isRegularFile(levelDatPath)) {
            levelDat = BedrockLevelDat.parse(Files.readAllBytes(levelDatPath));
        }

        String name = folder.getFileName().toString();
        Path levelName = folder.resolve("levelname.txt");
        if (Files.isRegularFile(levelName)) {
            String text = Files.readString(levelName, StandardCharsets.UTF_8).trim();
            if (!text.isEmpty()) {
                name = text;
            }
        }

        LevelDb db = LevelDb.open(dbDir, progress);
        BedrockWorld world = new BedrockWorld(folder, name, db, levelDat);
        progress.update("Organising chunks...", 1);
        world.buildIndex();
        return world;
    }

    static Path resolveWorldFolder(Path path) {
        Path p = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(p)) {
            p = p.getParent();
        }
        if (p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase("db")
                && Files.isRegularFile(p.resolve("CURRENT"))) {
            p = p.getParent();
        }
        if (!Files.isDirectory(p.resolve("db"))) {
            throw new LevelDbException(p + " does not look like a Bedrock world (no db folder). "
                    + "Choose the world's folder inside minecraftWorlds.");
        }
        return p;
    }

    private void buildIndex() {
        for (ByteKey key : db.keys()) {
            WorldKey parsed = WorldKey.parse(key);
            switch (parsed) {
                case WorldKey.Chunk c -> {
                    chunks.get(c.pos().dimension()).add(c.pos());
                    chunkKeys.computeIfAbsent(c.pos(), p -> new ArrayList<>()).add(key);
                }
                case WorldKey.Digest d -> {
                    // Entities live under actorprefix keys; the digest is resolved when the chunk is opened
                    chunks.get(d.pos().dimension()).add(d.pos());
                }
                case WorldKey.Actor a -> {
                    // Reached through their chunk's digest
                }
                case WorldKey.Named n -> otherRecords.add(new WorldRecord(key, n.name(), RecordKind.NBT, null));
            }
        }
        otherRecords.sort(Comparator.comparing(WorldRecord::label, String.CASE_INSENSITIVE_ORDER));
    }

    // ------------------------------------------------------------------
    // Browsing

    public Path getFolder() {
        return folder;
    }

    public String getName() {
        return name;
    }

    public boolean hasLevelDat() {
        return levelDat != null;
    }

    /** Chunks present in a dimension, sorted by x then z */
    public NavigableSet<ChunkPos> getChunks(Dimension dimension) {
        return Collections.unmodifiableNavigableSet(chunks.get(dimension));
    }

    public int getChunkCount() {
        return chunks.values().stream().mapToInt(NavigableSet::size).sum();
    }

    /** World-wide records that are not tied to a chunk (players, maps, scoreboard...) */
    public List<WorldRecord> getOtherRecords() {
        return Collections.unmodifiableList(otherRecords);
    }

    /**
     * The records of one chunk: its own records ordered by type and
     * sub-chunk height, followed by its entities.
     */
    public List<WorldRecord> getChunkRecords(ChunkPos pos) throws IOException {
        List<WorldKey.Chunk> parsed = new ArrayList<>();
        Map<WorldKey.Chunk, ByteKey> keyOf = new HashMap<>();
        for (ByteKey key : chunkKeys.getOrDefault(pos, List.of())) {
            WorldKey.Chunk c = (WorldKey.Chunk) WorldKey.parse(key);
            parsed.add(c);
            keyOf.put(c, key);
        }
        parsed.sort(Comparator.comparingInt(WorldKey.Chunk::tag).thenComparingInt(WorldKey.Chunk::subChunkIndex));

        List<WorldRecord> records = new ArrayList<>();
        for (WorldKey.Chunk c : parsed) {
            String label;
            RecordKind kind;
            if (c.type() == null) {
                // A tag this editor does not know yet; show its bytes
                label = String.format("Tag 0x%02x", c.tag());
                kind = RecordKind.BINARY;
            } else if (c.type() == ChunkRecordType.SUB_CHUNK) {
                label = "SubChunk y=" + c.subChunkIndex() + " (blocks " + (c.subChunkIndex() * 16) + " to "
                        + (c.subChunkIndex() * 16 + 15) + ")";
                kind = c.type().getKind();
            } else {
                label = c.type().getDisplayName();
                kind = c.type().getKind();
            }
            records.add(new WorldRecord(keyOf.get(c), label, kind, pos));
        }

        // Modern worlds: digp lists 8-byte actor ids, each stored under actorprefix + id
        byte[] digest = read(WorldKey.digestKey(pos));
        if (digest != null) {
            for (int i = 0; i + 8 <= digest.length; i += 8) {
                byte[] id = java.util.Arrays.copyOfRange(digest, i, i + 8);
                ByteKey actorKey = WorldKey.actorKey(id);
                if (db.contains(actorKey) || pending.containsKey(actorKey)) {
                    records.add(new WorldRecord(actorKey, "Entity " + actorLabel(actorKey), RecordKind.NBT, pos));
                }
            }
        }
        return records;
    }

    /** Entity label: its identifier when cheaply available, else its id */
    private String actorLabel(ByteKey actorKey) {
        WorldKey.Actor actor = (WorldKey.Actor) WorldKey.parse(actorKey);
        try {
            RecordContent content = load(new WorldRecord(actorKey, "", RecordKind.NBT, null));
            if (!content.documents().isEmpty()
                    && content.documents().get(0).getRoot().get("identifier") instanceof Tag.StringTag id) {
                return id.getValue().replace("minecraft:", "") + " (" + actor.idHex() + ")";
            }
        } catch (IOException | RuntimeException e) {
            // Fall back to the id
        }
        return actor.idHex();
    }

    /** Reads and decodes a record, including any unsaved edit */
    public RecordContent load(WorldRecord record) throws IOException {
        if (record.kind() == RecordKind.LEVEL_DAT) {
            if (levelDat == null) {
                throw new IOException("This world has no level.dat");
            }
            return new RecordContent(record, List.of(levelDat.getDocument()), true, null);
        }

        byte[] value = read(record.key());
        if (value == null) {
            throw new IOException("Record " + record.label() + " no longer exists");
        }
        return RecordDecoder.decode(record, value);
    }

    private byte[] read(ByteKey key) throws IOException {
        if (pending.containsKey(key)) {
            byte[] value = pending.get(key);
            return value == null ? null : value.clone();
        }
        return db.get(key);
    }

    // ------------------------------------------------------------------
    // Editing and saving

    /**
     * Records an edited record. The documents are serialized immediately so
     * later changes to the objects do not leak into the pending write.
     */
    public void recordEdited(RecordContent content) {
        if (!content.editable()) {
            throw new IllegalArgumentException(content.record().label() + " is read-only");
        }
        if (content.record().kind() == RecordKind.LEVEL_DAT) {
            levelDat = new BedrockLevelDat(levelDat.getStorageVersion(), content.documents().get(0));
            levelDatChanged = true;
            return;
        }
        pending.put(content.record().key(), NbtWriter.writeAll(content.documents()));
    }

    public boolean hasUnsavedChanges() {
        return levelDatChanged || !pending.isEmpty();
    }

    /** Number of records (level.dat counts as one) waiting to be saved */
    public int getUnsavedChangeCount() {
        return pending.size() + (levelDatChanged ? 1 : 0);
    }

    /** True if Minecraft (or another LevelDB program) has this world open */
    public boolean isInUse() {
        return LevelDb.isInUse(folder.resolve("db"));
    }

    /**
     * Writes all pending edits. Database records are written first, as a
     * new LevelDB log file; level.dat is then replaced atomically, keeping
     * the previous version as level.dat_old (as Minecraft does).
     */
    public void save() throws IOException {
        if (!hasUnsavedChanges()) {
            return;
        }
        if (isInUse()) {
            throw new LevelDbException("The world is open in Minecraft. Close it there before saving.");
        }

        if (!pending.isEmpty()) {
            db.write(pending);
            pending.clear();
        }

        if (levelDatChanged) {
            Path target = folder.resolve("level.dat");
            Path temp = folder.resolve("level.dat.anviltmp");
            Files.write(temp, levelDat.toBytes());
            if (Files.exists(target)) {
                Files.copy(target, folder.resolve("level.dat_old"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            levelDatChanged = false;
        }
    }

    /**
     * Saves a copy of the world's files on disk (not including unsaved
     * edits) as a .mcworld archive, which Minecraft can import directly.
     */
    public void backup(Path target) throws IOException {
        if (isInUse()) {
            throw new LevelDbException("The world is open in Minecraft. Close it there before backing up, "
                    + "or the backup may be inconsistent.");
        }

        Path temp = target.resolveSibling(target.getFileName() + ".anviltmp");
        try (OutputStream out = Files.newOutputStream(temp);
             ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = folder.relativize(file).toString().replace('\\', '/');
                if (name.equals("db/LOCK") || name.endsWith(".anviltmp") || file.equals(temp)) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Decodes level.dat's name tag, if present (falls back to the folder's levelname.txt name) */
    public String getLevelName() {
        if (levelDat != null && levelDat.getDocument().getRoot().get("LevelName") instanceof Tag.StringTag s) {
            return s.getValue();
        }
        return name;
    }
}
