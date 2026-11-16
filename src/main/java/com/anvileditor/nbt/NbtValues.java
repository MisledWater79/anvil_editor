package com.anvileditor.nbt;

import java.util.StringJoiner;

/**
 * Converts between tags and the text a user types or sees in the editor.
 *
 * Parsing is strict: a value outside the type's range (such as 300 for a
 * BYTE) is rejected rather than wrapped, because silently changing a
 * number could corrupt a world.
 */
public final class NbtValues {

    private NbtValues() {
    }

    /**
     * Parses user text into a new tag of the given type.
     * Arrays are comma separated, e.g. "1, 2, 3". Blank text is an empty array.
     *
     * @throws NbtFormatException if the type cannot be edited as text or the text is invalid
     */
    public static Tag parse(TagType type, String text) {
        try {
            switch (type) {
                case BYTE:
                    return new Tag.ByteTag(Byte.parseByte(text.trim()));
                case SHORT:
                    return new Tag.ShortTag(Short.parseShort(text.trim()));
                case INT:
                    return new Tag.IntTag(Integer.parseInt(text.trim()));
                case LONG:
                    return new Tag.LongTag(Long.parseLong(text.trim()));
                case FLOAT:
                    return new Tag.FloatTag(Float.parseFloat(text.trim()));
                case DOUBLE:
                    return new Tag.DoubleTag(Double.parseDouble(text.trim()));
                case STRING:
                    // Strings are not trimmed: leading/trailing spaces can be meaningful
                    return new Tag.StringTag(text);
                case BYTE_ARRAY: {
                    String[] parts = splitList(text);
                    byte[] bytes = new byte[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        bytes[i] = Byte.parseByte(parts[i]);
                    }
                    return new Tag.ByteArrayTag(bytes);
                }
                case INT_ARRAY: {
                    String[] parts = splitList(text);
                    int[] ints = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        ints[i] = Integer.parseInt(parts[i]);
                    }
                    return new Tag.IntArrayTag(ints);
                }
                default:
                    throw new NbtFormatException(type + " tags cannot be edited as text");
            }
        } catch (NumberFormatException e) {
            throw new NbtFormatException("\"" + text + "\" is not a valid " + type + " value", e);
        }
    }

    /**
     * Text form of a tag's value, suitable for an edit box.
     * Compounds and lists have no text form and return null.
     */
    public static String toText(Tag tag) {
        switch (tag.getType()) {
            case BYTE:
                return Byte.toString(((Tag.ByteTag) tag).getValue());
            case SHORT:
                return Short.toString(((Tag.ShortTag) tag).getValue());
            case INT:
                return Integer.toString(((Tag.IntTag) tag).getValue());
            case LONG:
                return Long.toString(((Tag.LongTag) tag).getValue());
            case FLOAT:
                return Float.toString(((Tag.FloatTag) tag).getValue());
            case DOUBLE:
                return Double.toString(((Tag.DoubleTag) tag).getValue());
            case STRING:
                return ((Tag.StringTag) tag).getValue();
            case BYTE_ARRAY: {
                StringJoiner joined = new StringJoiner(", ");
                for (byte b : ((Tag.ByteArrayTag) tag).getValue()) {
                    joined.add(Byte.toString(b));
                }
                return joined.toString();
            }
            case INT_ARRAY: {
                StringJoiner joined = new StringJoiner(", ");
                for (int i : ((Tag.IntArrayTag) tag).getValue()) {
                    joined.add(Integer.toString(i));
                }
                return joined.toString();
            }
            default:
                return null;
        }
    }

    private static String[] splitList(String text) {
        if (text.isBlank()) {
            return new String[0];
        }

        String[] parts = text.split(",", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }
}
