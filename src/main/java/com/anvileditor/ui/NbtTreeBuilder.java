package com.anvileditor.ui;

import com.anvileditor.nbt.NbtDocument;
import com.anvileditor.nbt.NbtPath;
import com.anvileditor.nbt.NbtValues;
import com.anvileditor.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TreeItem;

/**
 * Connects the NBT model (com.anvileditor.nbt) to the existing tree UI.
 *
 * It converts an NbtDocument into the TreeItem<NbtTreeNode> hierarchy that
 * NbtTreeView already displays, and maps a selected TreeItem back to an
 * NbtPath so edits can be applied to the model through NbtEditor.
 *
 * The com.anvileditor.nbt package itself has no JavaFX dependency; this
 * class is the only bridge.
 */
public final class NbtTreeBuilder {

    // Matches the hidden root used by NbtTreeView
    private static final String ROOT_TYPE = "ROOT";

    private NbtTreeBuilder() {
    }

    /**
     * Builds the tree for a document.
     * The returned item is the hidden "ROOT" container NbtTreeView expects;
     * its single child is the document's root compound.
     */
    public static TreeItem<NbtTreeNode> build(NbtDocument document) {
        return build(List.of(document));
    }

    /**
     * Builds the tree for a record holding several root compounds back to
     * back (block entities, legacy entities...). Each document becomes one
     * child of the hidden root, in order; documentIndexOf maps an item back.
     */
    public static TreeItem<NbtTreeNode> build(List<NbtDocument> documents) {
        TreeItem<NbtTreeNode> root = new TreeItem<>(new NbtTreeNode("NBT Data", ROOT_TYPE, null));

        for (int i = 0; i < documents.size(); i++) {
            NbtDocument document = documents.get(i);
            TreeItem<NbtTreeNode> item = createItem(rootLabel(document, i, documents.size()), document.getRoot());
            // Collapse long multi-root records so they read as a list
            item.setExpanded(documents.size() == 1);
            root.getChildren().add(item);
        }

        root.setExpanded(true);
        return root;
    }

    /**
     * A Bedrock root compound normally has an empty name, so show a label
     * instead: the record's "id"/"identifier" when it has one.
     */
    private static String rootLabel(NbtDocument document, int index, int count) {
        if (!document.getRootName().isEmpty()) {
            return document.getRootName();
        }
        String id = null;
        if (document.getRoot().get("identifier") instanceof Tag.StringTag s) {
            id = s.getValue();
        } else if (document.getRoot().get("id") instanceof Tag.StringTag s) {
            id = s.getValue();
        }
        if (count == 1) {
            return id == null ? "(root)" : "(root) " + id;
        }
        return "#" + index + (id == null ? "" : " " + id);
    }

    /**
     * Index of the document an item belongs to (0 for single-document trees).
     * Returns -1 for the hidden root itself.
     */
    public static int documentIndexOf(TreeItem<NbtTreeNode> item) {
        TreeItem<NbtTreeNode> current = item;
        if (current.getParent() == null) {
            return -1;
        }
        while (current.getParent().getParent() != null) {
            current = current.getParent();
        }
        return current.getParent().getChildren().indexOf(current);
    }

    /**
     * Re-creates an item's node and children from the model.
     * Call this on the affected item after an edit (for example on the parent
     * after adding or removing a child) so the display matches the data.
     */
    public static void refresh(TreeItem<NbtTreeNode> item, Tag tag) {
        TreeItem<NbtTreeNode> fresh = createItem(item.getValue().getName(), tag);
        item.setValue(fresh.getValue());
        item.getChildren().setAll(fresh.getChildren());
    }

    /**
     * Works out which tag in the model a tree item represents.
     * Items under a compound are identified by name, items under a list by position.
     */
    public static NbtPath pathOf(TreeItem<NbtTreeNode> item) {
        List<Object> segments = new ArrayList<>();
        TreeItem<NbtTreeNode> current = item;

        // The document root (a child of the hidden ROOT item) is the empty path
        while (current.getParent() != null && current.getParent().getParent() != null) {
            TreeItem<NbtTreeNode> parent = current.getParent();

            if ("LIST".equals(parent.getValue().getType())) {
                segments.add(parent.getChildren().indexOf(current));
            } else {
                segments.add(current.getValue().getName());
            }
            current = parent;
        }

        // Segments were collected leaf-first
        java.util.Collections.reverse(segments);
        return NbtPath.of(segments.toArray());
    }

    // ---------------------------------------------------------------

    private static TreeItem<NbtTreeNode> createItem(String name, Tag tag) {
        TreeItem<NbtTreeNode> item =
                new TreeItem<>(new NbtTreeNode(name, tag.getType().name(), displayValue(tag)));

        if (tag instanceof Tag.CompoundTag compound) {
            for (Map.Entry<String, Tag> entry : compound.getChildren().entrySet()) {
                item.getChildren().add(createItem(entry.getKey(), entry.getValue()));
            }
            item.setExpanded(true);
        } else if (tag instanceof Tag.ListTag list) {
            for (int i = 0; i < list.getElements().size(); i++) {
                item.getChildren().add(createItem("[" + i + "]", list.get(i)));
            }
            item.setExpanded(true);
        }

        return item;
    }

    /**
     * Value shown in the tree row. Compounds and lists show nothing (their
     * children are the content), and arrays show a size summary so a large
     * array does not flood the row.
     */
    private static Object displayValue(Tag tag) {
        if (tag instanceof Tag.ByteArrayTag bytes) {
            return "[" + bytes.length() + " bytes]";
        }
        if (tag instanceof Tag.IntArrayTag ints) {
            return "[" + ints.length() + " ints]";
        }
        return NbtValues.toText(tag);
    }
}
