package com.anvileditor.leveldb;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of puts and deletes applied atomically. This is the payload of
 * every record in a write-ahead log (*.log) file.
 *
 * Layout: sequence (fixed64), count (fixed32), then per operation a type
 * byte (1 = put, 0 = delete), the key, and for puts the value; keys and
 * values are varint-length-prefixed. Operation i gets sequence + i.
 */
final class WriteBatch {

    static final int TYPE_DELETE = 0;
    static final int TYPE_PUT = 1;

    /** One operation; value is null for a delete */
    record Op(byte[] key, byte[] value) {
        boolean isDelete() {
            return value == null;
        }
    }

    private final long sequence;
    private final List<Op> ops;

    WriteBatch(long sequence, List<Op> ops) {
        this.sequence = sequence;
        this.ops = List.copyOf(ops);
    }

    long sequence() {
        return sequence;
    }

    List<Op> ops() {
        return ops;
    }

    /** Sequence number of the last operation (the batch's highest) */
    long lastSequence() {
        return sequence + ops.size() - 1;
    }

    static WriteBatch decode(byte[] record) {
        ByteInput in = new ByteInput(record);
        long sequence = in.readFixed64();
        int count = in.readFixed32();
        if (count < 0) {
            throw new LevelDbException("Write batch has a negative operation count");
        }

        List<Op> ops = new ArrayList<>(Math.min(count, 1 << 16));
        for (int i = 0; i < count; i++) {
            int type = in.readUnsignedByte();
            byte[] key = in.readLengthPrefixed();
            switch (type) {
                case TYPE_PUT -> ops.add(new Op(key, in.readLengthPrefixed()));
                case TYPE_DELETE -> ops.add(new Op(key, null));
                default -> throw new LevelDbException("Unknown write batch operation type " + type);
            }
        }
        if (in.hasRemaining()) {
            throw new LevelDbException("Write batch has " + in.remaining() + " trailing bytes");
        }
        return new WriteBatch(sequence, ops);
    }

    byte[] encode() {
        ByteOutput out = new ByteOutput();
        out.writeFixed64(sequence);
        out.writeFixed32(ops.size());
        for (Op op : ops) {
            if (op.isDelete()) {
                out.writeByte(TYPE_DELETE);
                out.writeLengthPrefixed(op.key());
            } else {
                out.writeByte(TYPE_PUT);
                out.writeLengthPrefixed(op.key());
                out.writeLengthPrefixed(op.value());
            }
        }
        return out.toByteArray();
    }
}
