package com.qft.demandnotice.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Best-effort deletion of temporary files.
 *
 * <p>Microsoft Publisher keeps a handle on a .pub or .pdf for a moment after
 * the COM script that read or wrote it exits. Deleting such a file from a
 * {@code finally} block can therefore throw a {@code FileSystemException} and
 * turn a conversion that actually succeeded into a failure, so cleanup must
 * never propagate: a leftover temp file is harmless.</p>
 */
final class TempFiles {

    private TempFiles() {
    }

    /** Deletes {@code file} if possible, otherwise retries at JVM exit. */
    static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        file.toFile().deleteOnExit();
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Still locked by Publisher; the OS removes it after the handle
            // is released (deleteOnExit covers the common case).
        }
    }
}
