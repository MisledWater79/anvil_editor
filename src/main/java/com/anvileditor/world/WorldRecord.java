package com.anvileditor.world;

import com.anvileditor.leveldb.ByteKey;

/**
 * One viewable item of a world: a LevelDB record, or level.dat.
 *
 * @param key   The LevelDB key (null for level.dat)
 * @param label Name shown in the world tree
 * @param kind  How the value is decoded
 * @param chunk The chunk it belongs to, or null for world-wide records
 */
public record WorldRecord(ByteKey key, String label, RecordKind kind, ChunkPos chunk) {

    public static final WorldRecord LEVEL_DAT = new WorldRecord(null, "level.dat", RecordKind.LEVEL_DAT, null);

    @Override
    public String toString() {
        return label;
    }
}
