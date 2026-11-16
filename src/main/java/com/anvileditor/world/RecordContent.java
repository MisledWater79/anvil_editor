package com.anvileditor.world;

import com.anvileditor.nbt.NbtDocument;

import java.util.List;

/**
 * A record's decoded value, ready for the NBT tree.
 *
 * @param record    The record this came from
 * @param documents The NBT roots to display (several for multi-root records)
 * @param editable  False for decoded summaries of non-NBT data, which cannot be saved back
 * @param note      Optional explanation shown to the user (e.g. why it is read-only)
 */
public record RecordContent(WorldRecord record, List<NbtDocument> documents, boolean editable, String note) {

    public RecordContent {
        documents = List.copyOf(documents);
    }
}
