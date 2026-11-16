package com.anvileditor.world;

import com.anvileditor.nbt.NbtDocument;
import com.anvileditor.nbt.NbtFormatException;
import com.anvileditor.nbt.NbtReader;
import com.anvileditor.nbt.Tag;
import com.anvileditor.nbt.TagType;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Turns record bytes into NBT documents the tree view can show.
 *
 * NBT records decode directly and can be edited. Sub-chunks and other
 * binary records are summarised as a synthetic, read-only compound so
 * they can be inspected in the same view.
 */
final class RecordDecoder {

    private static final int HEX_PREVIEW_BYTES = 256;

    private RecordDecoder() {
    }

    static RecordContent decode(WorldRecord record, byte[] value) {
        return switch (record.kind()) {
            case NBT, LEVEL_DAT -> decodeNbt(record, value);
            case SUBCHUNK -> decodeSubChunk(record, value);
            case BINARY -> binary(record, value, "Binary data is shown read-only.");
        };
    }

    private static RecordContent decodeNbt(WorldRecord record, byte[] value) {
        try {
            List<NbtDocument> documents = NbtReader.readAll(value);
            String note = documents.isEmpty() ? "This record is empty." : null;
            return new RecordContent(record, documents, true, note);
        } catch (NbtFormatException e) {
            return binary(record, value, "Could not read this record as NBT (" + e.getMessage()
                    + "), so it is shown read-only.");
        }
    }

    /**
     * Sub-chunk layout (versions 1, 8 and 9): version byte, storage count
     * (v8/v9), y index (v9), then per storage a header byte (bits per block
     * << 1), packed 32-bit words of palette indexes, a palette size, and the
     * palette entries as NBT compounds.
     */
    private static RecordContent decodeSubChunk(WorldRecord record, byte[] value) {
        try {
            int pos = 0;
            int version = value[pos++] & 0xFF;

            Tag.CompoundTag root = new Tag.CompoundTag();
            root.put("version", new Tag.ByteTag((byte) version));

            int storageCount;
            if (version == 8 || version == 9) {
                storageCount = value[pos++] & 0xFF;
                if (version == 9) {
                    root.put("yIndex", new Tag.ByteTag(value[pos++]));
                }
            } else if (version == 1) {
                storageCount = 1;
            } else {
                return binary(record, value, "Sub-chunk version " + version
                        + " uses the legacy block format, which is shown read-only.");
            }

            Tag.ListTag storages = new Tag.ListTag(TagType.COMPOUND);
            for (int s = 0; s < storageCount; s++) {
                int header = value[pos++] & 0xFF;
                int bitsPerBlock = header >> 1;

                Tag.CompoundTag storage = new Tag.CompoundTag();
                storage.put("bitsPerBlock", new Tag.ByteTag((byte) bitsPerBlock));

                if (bitsPerBlock > 0) {
                    int blocksPerWord = 32 / bitsPerBlock;
                    int wordCount = (4096 + blocksPerWord - 1) / blocksPerWord;
                    pos += wordCount * 4;
                }

                int paletteSize;
                if (bitsPerBlock == 0 && pos < value.length && value[pos] == TagType.COMPOUND.getId()) {
                    paletteSize = 1; // single-block storage with no size prefix
                } else {
                    paletteSize = readIntLE(value, pos);
                    pos += 4;
                }
                if (paletteSize < 0 || paletteSize > 4096) {
                    throw new NbtFormatException("palette size " + paletteSize + " is out of range");
                }

                Tag.ListTag palette = new Tag.ListTag(TagType.COMPOUND);
                NbtReader reader = new NbtReader(Arrays.copyOfRange(value, pos, value.length));
                for (int i = 0; i < paletteSize; i++) {
                    palette.add(reader.readDocument().getRoot());
                }
                pos += reader.position();

                storage.put("paletteSize", new Tag.IntTag(paletteSize));
                storage.put("palette", palette);
                storages.add(storage);
            }
            root.put("storages", storages);

            return new RecordContent(record, List.of(new NbtDocument("SubChunk", root)), false,
                    "Sub-chunk block data is decoded for viewing and is read-only.");
        } catch (RuntimeException e) {
            return binary(record, value, "Could not decode this sub-chunk (" + e.getMessage()
                    + "), so it is shown read-only.");
        }
    }

    private static RecordContent binary(WorldRecord record, byte[] value, String note) {
        Tag.CompoundTag root = new Tag.CompoundTag();
        root.put("size", new Tag.IntTag(value.length));

        if (value.length == 1) {
            root.put("value", new Tag.ByteTag(value[0]));
        } else if (value.length == 4) {
            root.put("value", new Tag.IntTag(readIntLE(value, 0)));
        }

        int previewLength = Math.min(value.length, HEX_PREVIEW_BYTES);
        String hex = HexFormat.ofDelimiter(" ").formatHex(value, 0, previewLength);
        if (previewLength < value.length) {
            hex += " ... (" + (value.length - previewLength) + " more bytes)";
        }
        root.put("hex", new Tag.StringTag(hex));

        return new RecordContent(record, List.of(new NbtDocument(record.label(), root)), false, note);
    }

    private static int readIntLE(byte[] b, int pos) {
        if (pos + 4 > b.length) {
            throw new NbtFormatException("data ends early");
        }
        return (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
    }
}
