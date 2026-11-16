package com.anvileditor.nbt;

/**
 * Thrown when NBT data is malformed, truncated, or an edit is invalid.
 * Unchecked, so UI code can catch it in one place and show a message
 * instead of writing anything back to the world.
 */
public class NbtFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NbtFormatException(String message) {
        super(message);
    }

    public NbtFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
