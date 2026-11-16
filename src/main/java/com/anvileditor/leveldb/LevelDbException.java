package com.anvileditor.leveldb;

/**
 * Thrown when a LevelDB file is malformed or uses a feature this
 * implementation does not support.
 *
 * I/O failures (missing files, permission problems) are reported as
 * IOException instead, so callers can tell "the data is bad" apart from
 * "the disk could not be read".
 */
public class LevelDbException extends RuntimeException {

    public LevelDbException(String message) {
        super(message);
    }

    public LevelDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
