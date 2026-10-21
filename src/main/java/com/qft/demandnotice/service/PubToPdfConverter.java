package com.qft.demandnotice.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Converts a Microsoft Publisher (.pub) file to PDF by driving the locally
 * installed Microsoft Publisher through PowerShell COM automation.
 *
 * <p>This requires Microsoft Publisher to be installed (the norm for machines
 * that author .pub files). Conversion typically takes a few seconds.</p>
 */
public final class PubToPdfConverter {

    private static final String SCRIPT_RESOURCE = "/scripts/pub_to_pdf.ps1";
    private static final long TIMEOUT_SECONDS = 240;

    private PubToPdfConverter() {
    }

    /**
     * Converts {@code pubFile} to PDF at {@code pdfOut} (overwritten if present).
     *
     * <p>The .pub is first copied to a temp file so the conversion works even
     * while the original is open in Microsoft Publisher (which would otherwise
     * block the open with an in-use prompt).</p>
     *
     * @throws IOException if Publisher is unavailable or conversion fails
     */
    public static void convert(Path pubFile, Path pdfOut) throws IOException {
        if (!Files.isRegularFile(pubFile)) {
            throw new IOException("Publisher file not found: " + pubFile);
        }
        Path script = extractScript();

        // Convert a copy: avoids "file in use" prompts when the .pub is open in Publisher.
        Path pubCopy = Files.createTempFile("qft_pub_copy_", ".pub");
        try {
            Files.copy(pubFile, pubCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass",
                    "-File", script.toAbsolutePath().toString(),
                    "-PubPath", pubCopy.toAbsolutePath().toString(),
                    "-PdfPath", pdfOut.toAbsolutePath().toString());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try {
                boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Publisher conversion timed out after "
                            + TIMEOUT_SECONDS + " seconds. If Microsoft Publisher is open, "
                            + "close it and try again.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("Publisher conversion was interrupted.", e);
            }

            if (process.exitValue() != 0 || !Files.isRegularFile(pdfOut)) {
                throw new IOException("Publisher could not convert the file.\n"
                        + "Make sure Microsoft Publisher is installed and the .pub file opens without "
                        + "prompts.\n\nPowerShell output:\n" + output.trim());
            }
        } finally {
            Files.deleteIfExists(pubCopy);
        }
    }

    private static Path extractScript() throws IOException {
        try (InputStream in = PubToPdfConverter.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled conversion script missing: " + SCRIPT_RESOURCE);
            }
            Path script = Files.createTempFile("qft_pub_to_pdf_", ".ps1");
            Files.write(script, in.readAllBytes());
            script.toFile().deleteOnExit();
            return script;
        }
    }
}
