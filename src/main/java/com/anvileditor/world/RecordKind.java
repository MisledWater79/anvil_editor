package com.anvileditor.world;

/** How a record's value is decoded for display */
public enum RecordKind {
    /** One or more little-endian NBT compounds back to back; editable */
    NBT,
    /** Block storage with an NBT block palette; shown read-only */
    SUBCHUNK,
    /** Other binary data; shown read-only as a summary */
    BINARY,
    /** The world's level.dat file (not stored in LevelDB); editable */
    LEVEL_DAT
}
