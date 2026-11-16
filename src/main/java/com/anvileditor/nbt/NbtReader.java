package com.anvileditor.nbt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Bedrock Edition NBT from a byte array.
 *
 * Bedrock stores NBT in little-endian byte order, uncompressed, with string
 * lengths as unsigned 16-bit values. (Java Edition is big-endian and
 * usually gzip-compressed, so Java NBT libraries cannot be used as-is.)
 *
 * This class only understands NBT bytes. Where those bytes come from
 * (a LevelDB record, level.dat after its header) is the caller's concern.
 *
 * Malformed input throws NbtFormatException; it never returns partial data.
 */
public final class NbtReader {

    // Protects against stack overflow from maliciously nested data
    private static final int MAX_DEPTH = 512;

    private final ByteBuffer buffer;

    /**
     * @param data Bytes containing one or more NBT documents
     */
    public NbtReader(byte[] data) {
        this.buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Reads every document in the data, one after another.
     * Several records in Bedrock store multiple root compounds back to back.
     *
     * @throws NbtFormatException if any bytes remain that are not valid NBT
     */
    public static List<NbtDocument> readAll(byte[] data) {
        NbtReader reader = new NbtReader(data);
        List<NbtDocument> documents = new ArrayList<>();

        while (reader.hasRemaining()) {
            documents.add(reader.readDocument());
        }

        return documents;
    }

    /** True if unread bytes remain */
    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    /** Offset of the next unread byte */
    public int position() {
        return buffer.position();
    }

    /**
     * Reads the next document: a type byte, the root name, and the payload.
     * Bedrock roots are compounds; any other root type is rejected.
     */
    public NbtDocument readDocument() {
        int typeId = readUnsignedByte();
        TagType type = TagType.fromId(typeId);

        if (type != TagType.COMPOUND) {
            throw new NbtFormatException("Root tag must be a COMPOUND but was " + type);
        }

        String rootName = readString();
        Tag.CompoundTag root = readCompound(1);
        return new NbtDocument(rootName, root);
    }

    // ---------------------------------------------------------------

    private Tag readPayload(TagType type, int depth) {
        switch (type) {
            case BYTE:
                need(1);
                return new Tag.ByteTag(buffer.get());
            case SHORT:
                need(2);
                return new Tag.ShortTag(buffer.getShort());
            case INT:
                need(4);
                return new Tag.IntTag(buffer.getInt());
            case LONG:
                need(8);
                return new Tag.LongTag(buffer.getLong());
            case FLOAT:
                need(4);
                return new Tag.FloatTag(buffer.getFloat());
            case DOUBLE:
                need(8);
                return new Tag.DoubleTag(buffer.getDouble());
            case BYTE_ARRAY: {
                int length = readLength(1);
                byte[] bytes = new byte[length];
                buffer.get(bytes);
                return new Tag.ByteArrayTag(bytes);
            }
            case INT_ARRAY: {
                int length = readLength(4);
                int[] ints = new int[length];
                for (int i = 0; i < length; i++) {
                    ints[i] = buffer.getInt();
                }
                return new Tag.IntArrayTag(ints);
            }
            case STRING:
                return new Tag.StringTag(readString());
            case LIST:
                return readList(depth);
            case COMPOUND:
                return readCompound(depth);
            default:
                throw new NbtFormatException("Cannot read a tag of type " + type);
        }
    }

    private Tag.CompoundTag readCompound(int depth) {
        checkDepth(depth);
        Tag.CompoundTag compound = new Tag.CompoundTag();

        while (true) {
            TagType type = TagType.fromId(readUnsignedByte());

            if (type == TagType.END) {
                return compound;
            }

            String name = readString();

            // Silently keeping only one of two equal names would lose data
            if (compound.getChildren().containsKey(name)) {
                throw new NbtFormatException("Duplicate tag name in compound: \"" + name + "\"");
            }

            compound.put(name, readPayload(type, depth + 1));
        }
    }

    private Tag.ListTag readList(int depth) {
        checkDepth(depth);
        TagType elementType = TagType.fromId(readUnsignedByte());

        need(4);
        int length = buffer.getInt();

        if (length < 0) {
            throw new NbtFormatException("Negative list length: " + length);
        }
        if (elementType == TagType.END && length > 0) {
            throw new NbtFormatException("List declares " + length + " elements of type END");
        }

        // Every element takes at least one byte except END, so this bounds the loop by the data size
        if (elementType != TagType.END && length > buffer.remaining()) {
            throw new NbtFormatException("List length " + length + " exceeds the remaining data");
        }

        Tag.ListTag list = new Tag.ListTag(elementType);
        for (int i = 0; i < length; i++) {
            list.getElements().add(readPayload(elementType, depth + 1));
        }
        return list;
    }

    private String readString() {
        need(2);
        int length = buffer.getShort() & 0xFFFF;
        need(length);

        // Read through a strict decoder so invalid UTF-8 is reported, not silently replaced
        ByteBuffer slice = buffer.slice();
        slice.limit(length);

        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(slice)
                    .toString();
            buffer.position(buffer.position() + length);
            return value;
        } catch (CharacterCodingException e) {
            throw new NbtFormatException("String is not valid UTF-8", e);
        }
    }

    /** Reads an int array/byte array length and checks that the data really holds that many entries */
    private int readLength(int bytesPerEntry) {
        need(4);
        int length = buffer.getInt();

        if (length < 0) {
            throw new NbtFormatException("Negative array length: " + length);
        }
        if ((long) length * bytesPerEntry > buffer.remaining()) {
            throw new NbtFormatException("Array length " + length + " exceeds the remaining data");
        }
        return length;
    }

    private int readUnsignedByte() {
        need(1);
        return buffer.get() & 0xFF;
    }

    private void need(int count) {
        if (buffer.remaining() < count) {
            throw new NbtFormatException("Unexpected end of NBT data at offset " + buffer.position());
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new NbtFormatException("NBT is nested deeper than " + MAX_DEPTH + " levels");
        }
    }
}
