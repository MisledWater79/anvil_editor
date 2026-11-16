package com.anvileditor.world;

import com.anvileditor.leveldb.ByteKey;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Interprets the raw LevelDB keys of a Bedrock world.
 *
 * Bedrock mixes several key shapes in one database:
 *   - chunk records: x, z (int32 LE), optional dimension (int32 LE),
 *     a record-type tag byte, and for sub-chunks a signed y index byte
 *   - "digp" + chunk coordinates: the list of entities in a chunk
 *   - "actorprefix" + 8-byte id: one entity's NBT
 *   - plain names such as "~local_player", "portals", "map_-123"
 */
public sealed interface WorldKey {

    byte[] DIGEST_PREFIX = "digp".getBytes(StandardCharsets.US_ASCII);
    byte[] ACTOR_PREFIX = "actorprefix".getBytes(StandardCharsets.US_ASCII);

    /**
     * A per-chunk record. type is null for a tag byte this editor does not
     * recognise; subChunkIndex is only meaningful for SUB_CHUNK.
     */
    record Chunk(ChunkPos pos, int tag, ChunkRecordType type, int subChunkIndex) implements WorldKey {
    }

    /** Chunk coordinates beyond this cannot occur (the world border is 30 million blocks out) */
    int MAX_CHUNK_COORD = 30_000_000 / 16 + 16;

    /** Unknown tags are only accepted in the range Bedrock uses for chunk records */
    int MIN_CHUNK_TAG = 0x2b;
    int MAX_CHUNK_TAG = 0x7f;

    /** "digp" key listing the actors (entities) stored for a chunk */
    record Digest(ChunkPos pos) implements WorldKey {
    }

    /** "actorprefix" key holding one entity */
    record Actor(byte[] id) implements WorldKey {
        public String idHex() {
            return HexFormat.of().formatHex(id);
        }
    }

    /** Any other key, shown by name */
    record Named(String name) implements WorldKey {
    }

    static WorldKey parse(ByteKey key) {
        byte[] b = key.toByteArray();

        if (key.startsWith(ACTOR_PREFIX) && b.length == ACTOR_PREFIX.length + 8) {
            byte[] id = new byte[8];
            System.arraycopy(b, ACTOR_PREFIX.length, id, 0, 8);
            return new Actor(id);
        }

        if (key.startsWith(DIGEST_PREFIX) && (b.length == 12 || b.length == 16)) {
            ByteBuffer buf = ByteBuffer.wrap(b, 4, b.length - 4).order(ByteOrder.LITTLE_ENDIAN);
            int x = buf.getInt();
            int z = buf.getInt();
            Dimension dim = b.length == 16 ? Dimension.fromId(buf.getInt()) : Dimension.OVERWORLD;
            if (dim != null) {
                return new Digest(new ChunkPos(dim, x, z));
            }
        }

        WorldKey chunk = parseChunk(b);
        if (chunk != null) {
            return chunk;
        }

        return new Named(displayName(b));
    }

    private static WorldKey parseChunk(byte[] b) {
        int len = b.length;
        if (len != 9 && len != 10 && len != 13 && len != 14) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int x = buf.getInt();
        int z = buf.getInt();
        if (Math.abs((long) x) > MAX_CHUNK_COORD || Math.abs((long) z) > MAX_CHUNK_COORD) {
            return null; // most likely a text key that happens to have this length
        }
        Dimension dim = Dimension.OVERWORLD;
        if (len >= 13) {
            dim = Dimension.fromId(buf.getInt());
            if (dim == null) {
                return null;
            }
        }

        int tag = buf.get() & 0xFF;
        ChunkRecordType type = ChunkRecordType.fromTag(tag);
        boolean hasIndex = len == 10 || len == 14;

        if (type == null) {
            if (hasIndex || tag < MIN_CHUNK_TAG || tag > MAX_CHUNK_TAG) {
                return null;
            }
        } else if (hasIndex != (type == ChunkRecordType.SUB_CHUNK)) {
            return null;
        }
        int index = hasIndex ? buf.get() : 0;

        return new Chunk(new ChunkPos(dim, x, z), tag, type, index);
    }

    /** Printable keys are shown as text, anything else as hex */
    private static String displayName(byte[] b) {
        for (byte c : b) {
            if (c < 0x20 || c > 0x7e) {
                return "0x" + HexFormat.of().formatHex(b);
            }
        }
        return new String(b, StandardCharsets.US_ASCII);
    }

    static ByteKey digestKey(ChunkPos pos) {
        boolean overworld = pos.dimension() == Dimension.OVERWORLD;
        ByteBuffer buf = ByteBuffer.allocate(overworld ? 12 : 16).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(DIGEST_PREFIX).putInt(pos.x()).putInt(pos.z());
        if (!overworld) {
            buf.putInt(pos.dimension().getId());
        }
        return ByteKey.of(buf.array());
    }

    static ByteKey actorKey(byte[] id) {
        byte[] key = new byte[ACTOR_PREFIX.length + id.length];
        System.arraycopy(ACTOR_PREFIX, 0, key, 0, ACTOR_PREFIX.length);
        System.arraycopy(id, 0, key, ACTOR_PREFIX.length, id.length);
        return ByteKey.of(key);
    }
}
