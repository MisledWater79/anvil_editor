package com.anvileditor.world;

/**
 * A chunk's coordinates (in chunks, i.e. block coordinates divided by 16)
 * within a dimension.
 */
public record ChunkPos(Dimension dimension, int x, int z) implements Comparable<ChunkPos> {

    @Override
    public int compareTo(ChunkPos other) {
        int c = dimension.compareTo(other.dimension);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(x, other.x);
        return c != 0 ? c : Integer.compare(z, other.z);
    }

    /** Short label used in the UI, e.g. "[3, -7]" */
    public String label() {
        return "[" + x + ", " + z + "]";
    }

    @Override
    public String toString() {
        return dimension.getDisplayName() + " " + label();
    }
}
