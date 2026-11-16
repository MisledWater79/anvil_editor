package com.anvileditor.world;

/**
 * The dimensions of a Bedrock world. The id is the value stored in chunk
 * keys (the Overworld's id is never stored; its keys simply omit it).
 */
public enum Dimension {
    OVERWORLD(0, "Overworld"),
    NETHER(1, "Nether"),
    END(2, "The End");

    private final int id;
    private final String displayName;

    Dimension(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Returns the dimension for a stored id, or null if it is not a known one */
    public static Dimension fromId(int id) {
        for (Dimension d : values()) {
            if (d.id == id) {
                return d;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
