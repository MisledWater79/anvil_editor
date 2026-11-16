package com.anvileditor.world;

/**
 * The per-chunk record types, identified by the tag byte in a chunk key.
 *
 * Names and ids follow the community documentation of the Bedrock level
 * format (minecraft.wiki "Bedrock Edition level format").
 */
public enum ChunkRecordType {
    DATA_3D(0x2b, "Data3D", RecordKind.BINARY),
    VERSION(0x2c, "Version", RecordKind.BINARY),
    DATA_2D(0x2d, "Data2D", RecordKind.BINARY),
    DATA_2D_LEGACY(0x2e, "Data2DLegacy", RecordKind.BINARY),
    SUB_CHUNK(0x2f, "SubChunk", RecordKind.SUBCHUNK),
    LEGACY_TERRAIN(0x30, "LegacyTerrain", RecordKind.BINARY),
    BLOCK_ENTITY(0x31, "Block Entities", RecordKind.NBT),
    ENTITY(0x32, "Entities (legacy)", RecordKind.NBT),
    PENDING_TICKS(0x33, "Pending Ticks", RecordKind.NBT),
    LEGACY_BLOCK_EXTRA_DATA(0x34, "LegacyBlockExtraData", RecordKind.BINARY),
    BIOME_STATE(0x35, "BiomeState", RecordKind.BINARY),
    FINALIZED_STATE(0x36, "FinalizedState", RecordKind.BINARY),
    CONVERSION_DATA(0x37, "ConversionData", RecordKind.BINARY),
    BORDER_BLOCKS(0x38, "BorderBlocks", RecordKind.BINARY),
    HARDCODED_SPAWNERS(0x39, "HardcodedSpawners", RecordKind.BINARY),
    RANDOM_TICKS(0x3a, "Random Ticks", RecordKind.NBT),
    CHECKSUMS(0x3b, "Checksums", RecordKind.BINARY),
    GENERATION_SEED(0x3c, "GenerationSeed", RecordKind.BINARY),
    GENERATED_PRE_CAVES_AND_CLIFFS_BLENDING(0x3d, "GeneratedPreCavesAndCliffsBlending", RecordKind.BINARY),
    BLENDING_BIOME_HEIGHT(0x3e, "BlendingBiomeHeight", RecordKind.BINARY),
    META_DATA_HASH(0x3f, "MetaDataHash", RecordKind.BINARY),
    BLENDING_DATA(0x40, "BlendingData", RecordKind.BINARY),
    ACTOR_DIGEST_VERSION(0x41, "ActorDigestVersion", RecordKind.BINARY),
    LEGACY_VERSION(0x76, "LegacyVersion", RecordKind.BINARY);

    private final int tag;
    private final String displayName;
    private final RecordKind kind;

    ChunkRecordType(int tag, String displayName, RecordKind kind) {
        this.tag = tag;
        this.displayName = displayName;
        this.kind = kind;
    }

    public int getTag() {
        return tag;
    }

    public String getDisplayName() {
        return displayName;
    }

    public RecordKind getKind() {
        return kind;
    }

    /** Returns the type for a tag byte, or null if the byte is not a chunk record tag */
    public static ChunkRecordType fromTag(int tag) {
        for (ChunkRecordType t : values()) {
            if (t.tag == tag) {
                return t;
            }
        }
        return null;
    }
}
