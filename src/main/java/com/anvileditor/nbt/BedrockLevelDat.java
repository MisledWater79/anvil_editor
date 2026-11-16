package com.anvileditor.nbt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Reads and writes a Bedrock world's level.dat file.
 *
 * Unlike LevelDB records, level.dat is a plain file: an 8-byte header
 * (storage version, then payload length, both little-endian int32)
 * followed by one little-endian NBT document.
 *
 * NOTE: this layout comes from the community-documented Bedrock level
 * format and has only been checked against hand-built test bytes, not yet
 * against a real world's level.dat.
 */
public final class BedrockLevelDat {

    private static final int HEADER_SIZE = 8;

    private final int storageVersion;
    private final NbtDocument document;

    public BedrockLevelDat(int storageVersion, NbtDocument document) {
        this.storageVersion = storageVersion;
        this.document = document;
    }

    public int getStorageVersion() {
        return storageVersion;
    }

    public NbtDocument getDocument() {
        return document;
    }

    /**
     * Parses the full contents of a level.dat file
     * @throws NbtFormatException if the header or NBT payload is invalid
     */
    public static BedrockLevelDat parse(byte[] fileBytes) {
        if (fileBytes.length < HEADER_SIZE) {
            throw new NbtFormatException("level.dat is shorter than its 8-byte header");
        }

        ByteBuffer header = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);
        int version = header.getInt();
        int payloadLength = header.getInt();

        if (payloadLength != fileBytes.length - HEADER_SIZE) {
            throw new NbtFormatException("level.dat header says the payload is " + payloadLength
                    + " bytes but the file holds " + (fileBytes.length - HEADER_SIZE));
        }

        byte[] payload = new byte[payloadLength];
        System.arraycopy(fileBytes, HEADER_SIZE, payload, 0, payloadLength);

        List<NbtDocument> documents = NbtReader.readAll(payload);
        if (documents.size() != 1) {
            throw new NbtFormatException("level.dat should hold exactly one NBT document but held " + documents.size());
        }

        return new BedrockLevelDat(version, documents.get(0));
    }

    /** Produces the bytes to write back to level.dat, header included */
    public byte[] toBytes() {
        byte[] payload = NbtWriter.write(document);

        ByteBuffer result = ByteBuffer.allocate(HEADER_SIZE + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(storageVersion);
        result.putInt(payload.length);
        result.put(payload);
        return result.array();
    }
}
