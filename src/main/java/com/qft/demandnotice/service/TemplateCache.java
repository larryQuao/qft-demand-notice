package com.qft.demandnotice.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Disk cache for converted Publisher templates.
 *
 * <p>Driving Microsoft Publisher takes 15-20 seconds per .pub, so
 * {@link TemplateStamper#prepare} would otherwise pay that cost on every app
 * start even when the template has not changed. The converted PDF (the exact
 * result of the pipeline, including auto-inserted tokens) is therefore stored
 * under the user's local app-data folder, keyed by a SHA-256 of the .pub
 * bytes. A changed, edited or re-saved .pub always hashes differently, so the
 * cache can never serve a stale letter; nothing in the cache is ever
 * auto-deleted or corrupted into use.</p>
 *
 * <p>All cache failures are silently swallowed: a missing, unreadable or
 * corrupt cache behaves exactly like the pre-cache pipeline. Original .pub
 * files are only ever read, never written.</p>
 */
final class TemplateCache {

    /**
     * Bump this when the conversion/tokenizing pipeline changes in a way that
     * alters the produced PDF, so previously cached files are abandoned.
     */
    static final int PIPELINE_VERSION = 1;

    private TemplateCache() {
    }

    /** A cached conversion result. */
    record Entry(byte[] pdf, List<String> replaced, List<String> missed) {
    }

    /**
     * Returns the cached conversion of {@code pubBytes} under {@code variant}
     * ("plain" or "tokenized"), or empty when absent or unusable.
     */
    static Optional<Entry> load(byte[] pubBytes, String variant) {
        try {
            Path pdf = file(pubBytes, variant, ".pdf");
            if (!Files.isRegularFile(pdf)) {
                return Optional.empty();
            }
            byte[] converted = Files.readAllBytes(pdf);
            if (converted.length == 0) {
                return Optional.empty();
            }
            Optional<Entry> entry = readEntry(converted, sidecar(pubBytes, variant));
            if (entry.isEmpty()) {
                // Unusable cache entry: drop it so the next run starts clean.
                Files.deleteIfExists(pdf);
            }
            return entry;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Stores a conversion result for {@code pubBytes}. Existing entries are
     * replaced. Failures are ignored; the cache is an optimization only.
     */
    static void store(byte[] pubBytes, String variant, byte[] pdf,
                      List<String> replaced, List<String> missed) {
        try {
            Files.createDirectories(directory());
            Path targetPdf = file(pubBytes, variant, ".pdf");
            Path targetMeta = sidecar(pubBytes, variant);

            // Write to temp files and move atomically, so a concurrent reader
            // never sees a half-written PDF.
            Path tempPdf = Files.createTempFile(directory(), "cache_", ".tmp");
            Path tempMeta = Files.createTempFile(directory(), "cache_", ".tmp");
            try {
                Files.write(tempPdf, pdf);
                Files.write(tempMeta, metaText(replaced, missed)
                        .getBytes(StandardCharsets.UTF_8));
                move(tempPdf, targetPdf);
                move(tempMeta, targetMeta);
            } finally {
                Files.deleteIfExists(tempPdf);
                Files.deleteIfExists(tempMeta);
            }
        } catch (IOException | NoSuchAlgorithmException | RuntimeException ignored) {
            // Cache storage is best-effort.
        }
    }

    /** Cache folder: {@code %LOCALAPPDATA%\QFT Demand Notice Generator\template-cache}. */
    private static Path directory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData != null && !localAppData.isBlank())
                ? Path.of(localAppData)
                : Path.of(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve(Path.of("QFT Demand Notice Generator", "template-cache"));
    }

    /** Content-addressed file name: variant + .pub digest + pipeline version. */
    private static Path file(byte[] pubBytes, String variant, String suffix)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(pubBytes);
        String name = variant + "-" + PIPELINE_VERSION + "-"
                + HexFormat.of().formatHex(digest.digest());
        return directory().resolve(name + suffix);
    }

    private static Path sidecar(byte[] pubBytes, String variant)
            throws IOException, NoSuchAlgorithmException {
        return file(pubBytes, variant, ".meta");
    }

    private static Optional<Entry> readEntry(byte[] converted, Path meta) {
        // A plain conversion carries no recognizer report.
        if (!Files.isRegularFile(meta)) {
            return Optional.of(new Entry(converted, List.of(), List.of()));
        }
        try {
            String text = Files.readString(meta, StandardCharsets.UTF_8);
            String replaced = "";
            String missed = "";
            for (String line : text.split("\n")) {
                if (line.startsWith("replaced=")) {
                    replaced = line.substring("replaced=".length()).trim();
                } else if (line.startsWith("missed=")) {
                    missed = line.substring("missed=".length()).trim();
                }
            }
            return Optional.of(new Entry(converted, decode(replaced), decode(missed)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String metaText(List<String> replaced, List<String> missed) {
        return "replaced=" + encode(replaced) + "\nmissed=" + encode(missed) + "\n";
    }

    private static String encode(List<String> values) {
        // Field names are simple words; the |-separator is enough.
        return String.join("|", values);
    }

    private static List<String> decode(String joined) {
        if (joined.isEmpty()) {
            return List.of();
        }
        return List.of(joined.split("\\|", -1));
    }

    /**
     * Moves {@code from} onto {@code to}, tolerating a losing race between two
     * concurrent stores (the file already existing is a success).
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(to);
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
