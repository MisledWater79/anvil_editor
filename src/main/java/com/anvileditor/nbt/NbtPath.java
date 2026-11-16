package com.anvileditor.nbt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Identifies one tag inside a document, starting from the root compound.
 *
 * Each segment is a String (a child name in a compound) or an Integer
 * (an index in a list). Names are stored as-is, so names containing
 * characters like '/' are safe. The empty path is the root itself.
 *
 * Example: NbtPath.of("Entities", 0, "Health")
 */
public final class NbtPath {

    public static final NbtPath ROOT = new NbtPath(List.of());

    private final List<Object> segments;

    private NbtPath(List<Object> segments) {
        this.segments = segments;
    }

    /** Builds a path from String names and Integer list indexes */
    public static NbtPath of(Object... segments) {
        List<Object> copy = new ArrayList<>();

        for (Object segment : segments) {
            if (!(segment instanceof String) && !(segment instanceof Integer)) {
                throw new IllegalArgumentException("Path segments must be String or Integer, got: " + segment);
            }
            copy.add(segment);
        }

        return new NbtPath(Collections.unmodifiableList(copy));
    }

    /** Returns a new path one level deeper */
    public NbtPath child(Object segment) {
        Object[] extended = new Object[segments.size() + 1];
        for (int i = 0; i < segments.size(); i++) {
            extended[i] = segments.get(i);
        }
        extended[segments.size()] = segment;
        return of(extended);
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    public int size() {
        return segments.size();
    }

    public Object get(int index) {
        return segments.get(index);
    }

    /** The last segment of a non-root path */
    public Object last() {
        if (isRoot()) {
            throw new IllegalStateException("The root path has no segments");
        }
        return segments.get(segments.size() - 1);
    }

    /** The path of the containing tag; the root has no parent */
    public NbtPath parent() {
        if (isRoot()) {
            throw new IllegalStateException("The root path has no parent");
        }
        return new NbtPath(segments.subList(0, segments.size() - 1));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof NbtPath p && p.segments.equals(segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }

    /** Readable form for error messages, e.g. Entities/[0]/Health */
    @Override
    public String toString() {
        if (isRoot()) {
            return "(root)";
        }

        StringBuilder text = new StringBuilder();
        for (Object segment : segments) {
            if (text.length() > 0) {
                text.append('/');
            }
            text.append(segment instanceof Integer ? "[" + segment + "]" : segment);
        }
        return text.toString();
    }
}
