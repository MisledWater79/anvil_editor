package com.anvileditor.nbt;

/**
 * Applies edits to an NbtDocument: change a value, add a tag, remove a tag.
 *
 * Every edit is validated before anything is changed, and edits touch only
 * the targeted tag, so unrelated tags keep their values and their order.
 * Invalid edits throw NbtFormatException and leave the document unchanged.
 *
 * The editor works on the in-memory model only. Turning the result back
 * into bytes is NbtWriter's job.
 */
public class NbtEditor {

    private final NbtDocument document;

    public NbtEditor(NbtDocument document) {
        this.document = document;
    }

    public NbtDocument getDocument() {
        return document;
    }

    /**
     * Finds the tag at a path. The root path returns the root compound.
     * @throws NbtFormatException if nothing exists at that path
     */
    public Tag get(NbtPath path) {
        Tag current = document.getRoot();

        for (int i = 0; i < path.size(); i++) {
            Object segment = path.get(i);

            if (current instanceof Tag.CompoundTag compound && segment instanceof String name) {
                current = compound.get(name);
            } else if (current instanceof Tag.ListTag list && segment instanceof Integer index) {
                if (index < 0 || index >= list.getElements().size()) {
                    throw new NbtFormatException("List index " + index + " is out of range at " + path);
                }
                current = list.get(index);
            } else {
                throw new NbtFormatException("Cannot follow " + describe(segment) + " from a "
                        + current.getType() + " tag at " + path);
            }

            if (current == null) {
                throw new NbtFormatException("No tag found at " + path);
            }
        }

        return current;
    }

    /**
     * Replaces the value of an existing tag. The new tag must have the same
     * type as the old one; to change a tag's type, remove it and add a new one.
     */
    public void replace(NbtPath path, Tag newTag) {
        if (path.isRoot()) {
            throw new NbtFormatException("The root tag cannot be replaced");
        }

        Tag old = get(path);
        if (old.getType() != newTag.getType()) {
            throw new NbtFormatException("Cannot replace a " + old.getType() + " tag with a "
                    + newTag.getType() + " tag at " + path);
        }

        Tag parent = get(path.parent());
        Object last = path.last();

        if (parent instanceof Tag.CompoundTag compound) {
            // put() on an existing key keeps the child's position
            compound.put((String) last, newTag);
        } else {
            ((Tag.ListTag) parent).set((Integer) last, newTag);
        }
    }

    /**
     * Sets a scalar, string, or array tag from text typed by the user,
     * keeping the tag's existing type.
     */
    public void setValueFromText(NbtPath path, String text) {
        Tag existing = get(path);
        replace(path, NbtValues.parse(existing.getType(), text));
    }

    /**
     * Adds a new named child to a compound.
     * @throws NbtFormatException if the parent is not a compound or the name is taken
     */
    public void add(NbtPath parentPath, String name, Tag tag) {
        if (!(get(parentPath) instanceof Tag.CompoundTag compound)) {
            throw new NbtFormatException("Tags with a name can only be added to a COMPOUND, not at " + parentPath);
        }

        // Refusing here keeps an add from silently overwriting existing data
        if (compound.getChildren().containsKey(name)) {
            throw new NbtFormatException("A tag named \"" + name + "\" already exists at " + parentPath);
        }

        compound.put(name, tag);
    }

    /**
     * Appends an element to a list. The element must match the list's type.
     * @throws NbtFormatException if the target is not a list or the type does not match
     */
    public void addToList(NbtPath listPath, Tag tag) {
        if (!(get(listPath) instanceof Tag.ListTag list)) {
            throw new NbtFormatException("Elements can only be added to a LIST, not at " + listPath);
        }

        list.add(tag);
    }

    /**
     * Removes a compound child or a list element.
     * @return The removed tag
     */
    public Tag remove(NbtPath path) {
        if (path.isRoot()) {
            throw new NbtFormatException("The root tag cannot be removed");
        }

        // Validates that the target exists before removing it
        get(path);

        Tag parent = get(path.parent());
        Object last = path.last();

        if (parent instanceof Tag.CompoundTag compound) {
            return compound.remove((String) last);
        }
        return ((Tag.ListTag) parent).remove((Integer) last);
    }

    private static String describe(Object segment) {
        return segment instanceof Integer ? "list index [" + segment + "]" : "name \"" + segment + "\"";
    }
}
