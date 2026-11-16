package com.anvileditor.nbt;

/**
 * The NBT tag types used by Minecraft: Bedrock Edition.
 *
 * The numeric id is the byte written before each tag in the binary format.
 * The enum names match the type strings already used by NbtTreeNode
 * (INT, STRING, COMPOUND, LIST, ...), so tree nodes can use name() directly.
 *
 * Bedrock defines ids 0-11. Java Edition's LONG_ARRAY (12) is not part of
 * the Bedrock format, so it is intentionally not supported here.
 */
public enum TagType {
    END(0),
    BYTE(1),
    SHORT(2),
    INT(3),
    LONG(4),
    FLOAT(5),
    DOUBLE(6),
    BYTE_ARRAY(7),
    STRING(8),
    LIST(9),
    COMPOUND(10),
    INT_ARRAY(11);

    private final int id;

    TagType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Finds the tag type for an id read from the binary data
     * @param id The type byte
     * @return The matching type
     * @throws NbtFormatException if the id is not a Bedrock tag type
     */
    public static TagType fromId(int id) {
        for (TagType type : values()) {
            if (type.id == id) {
                return type;
            }
        }

        throw new NbtFormatException("Unknown NBT tag type id: " + id);
    }
}
