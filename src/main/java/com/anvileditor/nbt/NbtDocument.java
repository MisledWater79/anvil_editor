package com.anvileditor.nbt;

/**
 * One complete NBT structure: a root compound plus the name the file gave it.
 *
 * Bedrock root tags are normally compounds with an empty name, but the name
 * is stored so a read/write cycle reproduces the original bytes.
 */
public class NbtDocument {
    private final String rootName;
    private final Tag.CompoundTag root;

    public NbtDocument(String rootName, Tag.CompoundTag root) {
        this.rootName = rootName;
        this.root = root;
    }

    public String getRootName() {
        return rootName;
    }

    public Tag.CompoundTag getRoot() {
        return root;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtDocument d && d.rootName.equals(rootName) && d.root.equals(root);
    }

    @Override
    public int hashCode() {
        return rootName.hashCode() * 31 + root.hashCode();
    }
}
