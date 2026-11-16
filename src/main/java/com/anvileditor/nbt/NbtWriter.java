package com.anvileditor.nbt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Writes NBT documents in Bedrock Edition's on-disk format:
 * little-endian, uncompressed, unsigned 16-bit string lengths.
 *
 * Children are written in the order the model holds them, which is the
 * order NbtReader read them in, so an unmodified document serializes back
 * to the same bytes.
 */
public final class NbtWriter {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private NbtWriter() {
    }

    /** Serializes one document */
    public static byte[] write(NbtDocument document) {
        NbtWriter writer = new NbtWriter();
        writer.writeDocument(document);
        return writer.out.toByteArray();
    }

    /** Serializes several documents back to back (for records holding multiple roots) */
    public static byte[] writeAll(List<NbtDocument> documents) {
        NbtWriter writer = new NbtWriter();
        for (NbtDocument document : documents) {
            writer.writeDocument(document);
        }
        return writer.out.toByteArray();
    }

    // ---------------------------------------------------------------

    private void writeDocument(NbtDocument document) {
        out.write(TagType.COMPOUND.getId());
        writeString(document.getRootName());
        writeCompound(document.getRoot());
    }

    private void writePayload(Tag tag) {
        switch (tag.getType()) {
            case BYTE:
                out.write(((Tag.ByteTag) tag).getValue());
                break;
            case SHORT:
                writeShort(((Tag.ShortTag) tag).getValue());
                break;
            case INT:
                writeInt(((Tag.IntTag) tag).getValue());
                break;
            case LONG:
                writeLong(((Tag.LongTag) tag).getValue());
                break;
            case FLOAT:
                // Raw bits keep NaN payloads and signed zero exactly as read
                writeInt(Float.floatToRawIntBits(((Tag.FloatTag) tag).getValue()));
                break;
            case DOUBLE:
                writeLong(Double.doubleToRawLongBits(((Tag.DoubleTag) tag).getValue()));
                break;
            case BYTE_ARRAY: {
                byte[] bytes = ((Tag.ByteArrayTag) tag).getValue();
                writeInt(bytes.length);
                out.writeBytes(bytes);
                break;
            }
            case INT_ARRAY: {
                int[] ints = ((Tag.IntArrayTag) tag).getValue();
                writeInt(ints.length);
                for (int value : ints) {
                    writeInt(value);
                }
                break;
            }
            case STRING:
                writeString(((Tag.StringTag) tag).getValue());
                break;
            case LIST:
                writeList((Tag.ListTag) tag);
                break;
            case COMPOUND:
                writeCompound((Tag.CompoundTag) tag);
                break;
            default:
                throw new NbtFormatException("Cannot write a tag of type " + tag.getType());
        }
    }

    private void writeCompound(Tag.CompoundTag compound) {
        for (Map.Entry<String, Tag> entry : compound.getChildren().entrySet()) {
            out.write(entry.getValue().getType().getId());
            writeString(entry.getKey());
            writePayload(entry.getValue());
        }
        out.write(TagType.END.getId());
    }

    private void writeList(Tag.ListTag list) {
        out.write(list.getElementType().getId());
        writeInt(list.getElements().size());

        for (Tag element : list.getElements()) {
            // The model checks this on add/set, but a bad list must never reach the disk
            if (element.getType() != list.getElementType()) {
                throw new NbtFormatException("List of " + list.getElementType()
                        + " contains a " + element.getType());
            }
            writePayload(element);
        }
    }

    private void writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > 0xFFFF) {
            throw new NbtFormatException("String is too long for NBT (" + bytes.length + " bytes, max 65535)");
        }

        writeShort((short) bytes.length);
        out.writeBytes(bytes);
    }

    private void writeShort(short value) {
        out.writeBytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array());
    }

    private void writeInt(int value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
    }

    private void writeLong(long value) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }
}
