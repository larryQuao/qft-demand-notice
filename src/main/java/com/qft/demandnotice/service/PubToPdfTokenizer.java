package com.qft.demandnotice.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Inserts {@code {{...}}} placeholders into a Publisher .pub file that is
 * already a filled-in letter, by recognising the hard-coded values (date,
 * amount, period and deadline phrasing) through Publisher COM automation.
 *
 * <p>The caller passes a copy of the original .pub; the copy is then opened,
 * edited and saved in place, so the user's file is never modified.</p>
 */
public final class PubToPdfTokenizer {

    private static final String SCRIPT_RESOURCE = "/scripts/tokenize_pub.ps1";
    private static final long TIMEOUT_SECONDS = 240;

    private PubToPdfTokenizer() {
    }

    /**
     * Which fields the recognizer could fill: the report from the script.
     *
     * @param replaced          fields whose placeholder was inserted, e.g. "AMOUNT"
     * @param missed             fields no matching text was found for
     * @param alreadyTokenized   true when the document already carried {{...}}
     *                           tokens and nothing was inserted
     */
    public record TokenizeResult(List<String> replaced, List<String> missed,
                                 boolean alreadyTokenized) {
        public TokenizeResult {
            replaced = List.copyOf(replaced);
            missed = List.copyOf(missed);
        }
    }

    /**
     * Inserts placeholders into {@code pubFile} (in place).
     *
     * @return the recognizer report
     * @throws IOException if Publisher is unavailable or the script failed
     */
    public static TokenizeResult tokenize(Path pubFile) throws IOException {
        if (!Files.isRegularFile(pubFile)) {
            throw new IOException("Publisher file not found: " + pubFile);
        }
        Path script = extractScript();

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", script.toAbsolutePath().toString(),
                "-PubPath", pubFile.toAbsolutePath().toString());
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Drain the merged output while the process runs, same as in
        // PubToPdfConverter - reading only after waitFor() could deadlock.
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(captured);
            } catch (IOException ignored) {
                // the pipe closes when the process exits
            }
        }, "pub-tokenizer-output");
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            drain.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Placeholder auto-fill was interrupted.", e);
        }

        String output = captured.toString(StandardCharsets.UTF_8);
        if (!finished) {
            throw new IOException("Placeholder auto-fill timed out after "
                    + TIMEOUT_SECONDS + " seconds. If Microsoft Publisher is open, "
                    + "close it and try again.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Publisher could not be driven to add the "
                    + "placeholders.\n\nPowerShell output:\n" + output.trim());
        }
        return parseReport(output);
    }

    /** Pulls the {@code QFT_AUTO_TOKENIZE ...} line out of the script output. */
    static TokenizeResult parseReport(String output) throws IOException {
        List<String> replaced = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        boolean already = false;
        boolean scriptSaidSomething = false;

        for (String line : output.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("QFT_AUTO_TOKENIZE ")) {
                continue;
            }
            scriptSaidSomething = true;
            for (String part : trimmed.substring("QFT_AUTO_TOKENIZE ".length()).split("\\s+")) {
                int eq = part.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = part.substring(0, eq);
                String value = eq + 1 <= part.length() ? part.substring(eq + 1) : "";
                List<String> items = java.util.Arrays.stream(value.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                switch (key) {
                    case "replaced" -> replaced.addAll(items);
                    case "missed" -> missed.addAll(items);
                    case "alreadyTokenized" -> already = Boolean.parseBoolean(value);
                    default -> { /* ignore unknown keys */ }
                }
            }
        }

        if (!scriptSaidSomething) {
            throw new IOException(
                    "The auto-fill script produced no report.\n\nPowerShell output:\n"
                            + output.trim());
        }
        return new TokenizeResult(replaced, missed, already);
    }

    private static Path extractScript() throws IOException {
        try (InputStream in = PubToPdfTokenizer.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled script missing: " + SCRIPT_RESOURCE);
            }
            Path script = Files.createTempFile("qft_tokenize_pub_", ".ps1");
            Files.write(script, in.readAllBytes());
            script.toFile().deleteOnExit();
            return script;
        }
    }
}
