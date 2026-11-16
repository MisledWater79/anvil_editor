package com.anvileditor.nbt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for every NBT tag.
 *
 * Scalar, string, and array tags are immutable: editing a value means
 * replacing the tag in its parent (NbtEditor does this). Compound and
 * list tags are mutable containers so that unrelated children are never
 * rebuilt or reordered when one child changes.
 *
 * All concrete tag types are nested here to keep the model in one file.
 */
public abstract class Tag {

    /** Returns this tag's NBT type */
    public abstract TagType getType();

    // ---------------------------------------------------------------
    // Scalars
    // ---------------------------------------------------------------

    public static final class ByteTag extends Tag {
        private final byte value;

        public ByteTag(byte value) { this.value = value; }
        public byte getValue() { return value; }
        @Override public TagType getType() { return TagType.BYTE; }
        @Override public boolean equals(Object o) { return o instanceof ByteTag t && t.value == value; }
        @Override public int hashCode() { return Byte.hashCode(value); }
    }

    public static final class ShortTag extends Tag {
        private final short value;

        public ShortTag(short value) { this.value = value; }
        public short getValue() { return value; }
        @Override public TagType getType() { return TagType.SHORT; }
        @Override public boolean equals(Object o) { return o instanceof ShortTag t && t.value == value; }
        @Override public int hashCode() { return Short.hashCode(value); }
    }

    public static final class IntTag extends Tag {
        private final int value;

        public IntTag(int value) { this.value = value; }
        public int getValue() { return value; }
        @Override public TagType getType() { return TagType.INT; }
        @Override public boolean equals(Object o) { return o instanceof IntTag t && t.value == value; }
        @Override public int hashCode() { return Integer.hashCode(value); }
    }

    public static final class LongTag extends Tag {
        private final long value;

        public LongTag(long value) { this.value = value; }
        public long getValue() { return value; }
        @Override public TagType getType() { return TagType.LONG; }
        @Override public boolean equals(Object o) { return o instanceof LongTag t && t.value == value; }
        @Override public int hashCode() { return Long.hashCode(value); }
    }

    public static final class FloatTag extends Tag {
        private final float value;

        public FloatTag(float value) { this.value = value; }
        public float getValue() { return value; }
        @Override public TagType getType() { return TagType.FLOAT; }
        // Compare raw bits so NaN equals NaN and 0.0 differs from -0.0 (exact preservation)
        @Override public boolean equals(Object o) {
            return o instanceof FloatTag t && Float.floatToRawIntBits(t.value) == Float.floatToRawIntBits(value);
        }
        @Override public int hashCode() { return Float.floatToRawIntBits(value); }
    }

    public static final class DoubleTag extends Tag {
        private final double value;

        public DoubleTag(double value) { this.value = value; }
        public double getValue() { return value; }
        @Override public TagType getType() { return TagType.DOUBLE; }
        @Override public boolean equals(Object o) {
            return o instanceof DoubleTag t && Double.doubleToRawLongBits(t.value) == Double.doubleToRawLongBits(value);
        }
        @Override public int hashCode() { return Long.hashCode(Double.doubleToRawLongBits(value)); }
    }

    public static final class StringTag extends Tag {
        private final String value;

        public StringTag(String value) {
            if (value == null) {
                throw new NbtFormatException("String tag value cannot be null");
            }
            this.value = value;
        }
        public String getValue() { return value; }
        @Override public TagType getType() { return TagType.STRING; }
        @Override public boolean equals(Object o) { return o instanceof StringTag t && t.value.equals(value); }
        @Override public int hashCode() { return value.hashCode(); }
    }

    // ---------------------------------------------------------------
    // Arrays (defensively copied, so the tag stays immutable)
    // ---------------------------------------------------------------

    public static final class ByteArrayTag extends Tag {
        private final byte[] value;

        public ByteArrayTag(byte[] value) { this.value = value.clone(); }
        public byte[] getValue() { return value.clone(); }
        public int length() { return value.length; }
        @Override public TagType getType() { return TagType.BYTE_ARRAY; }
        @Override public boolean equals(Object o) { return o instanceof ByteArrayTag t && Arrays.equals(t.value, value); }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    public static final class IntArrayTag extends Tag {
        private final int[] value;

        public IntArrayTag(int[] value) { this.value = value.clone(); }
        public int[] getValue() { return value.clone(); }
        public int length() { return value.length; }
        @Override public TagType getType() { return TagType.INT_ARRAY; }
        @Override public boolean equals(Object o) { return o instanceof IntArrayTag t && Arrays.equals(t.value, value); }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }

    // ---------------------------------------------------------------
    // Containers
    // ---------------------------------------------------------------

    /**
     * A compound: named children in the order they were read.
     * LinkedHashMap keeps that order so a read/modify/write cycle does not
     * shuffle unrelated tags.
     */
    public static final class CompoundTag extends Tag {
        private final Map<String, Tag> children = new LinkedHashMap<>();

        @Override public TagType getType() { return TagType.COMPOUND; }

        /** Live view of the children, in file order */
        public Map<String, Tag> getChildren() { return children; }

        public Tag get(String name) { return children.get(name); }

        /** Adds a child, or replaces the existing one in place (order is kept) */
        public void put(String name, Tag tag) {
            if (name == null || tag == null) {
                throw new NbtFormatException("Compound child name and tag cannot be null");
            }
            children.put(name, tag);
        }

        public Tag remove(String name) { return children.remove(name); }

        @Override public boolean equals(Object o) {
            // Map equality ignores order; compare order explicitly too
            return o instanceof CompoundTag t
                    && t.children.equals(children)
                    && new ArrayList<>(t.children.keySet()).equals(new ArrayList<>(children.keySet()));
        }
        @Override public int hashCode() { return children.hashCode(); }
    }

    /**
     * A list of unnamed tags that all share one element type.
     * The element type is stored explicitly because an empty list still
     * has one in the file, and it must survive a round trip.
     */
    public static final class ListTag extends Tag {
        private TagType elementType;
        private final List<Tag> elements = new ArrayList<>();

        /** Creates an empty list whose elements will be of the given type */
        public ListTag(TagType elementType) {
            this.elementType = elementType;
        }

        @Override public TagType getType() { return TagType.LIST; }

        public TagType getElementType() { return elementType; }

        /** Live view of the elements */
        public List<Tag> getElements() { return elements; }

        public Tag get(int index) { return elements.get(index); }

        /** Adds an element; an empty END-typed list adopts the first element's type */
        public void add(Tag tag) {
            if (tag == null) {
                throw new NbtFormatException("List element cannot be null");
            }
            if (elements.isEmpty() && elementType == TagType.END) {
                elementType = tag.getType();
            }
            checkType(tag);
            elements.add(tag);
        }

        /** Replaces an element with one of the same type */
        public void set(int index, Tag tag) {
            checkType(tag);
            elements.set(index, tag);
        }

        public Tag remove(int index) { return elements.remove(index); }

        private void checkType(Tag tag) {
            if (tag.getType() != elementType) {
                throw new NbtFormatException("List holds " + elementType
                        + " tags but was given a " + tag.getType() + " tag");
            }
        }

        @Override public boolean equals(Object o) {
            return o instanceof ListTag t && t.elementType == elementType && t.elements.equals(elements);
        }
        @Override public int hashCode() { return elements.hashCode(); }
    }
}
