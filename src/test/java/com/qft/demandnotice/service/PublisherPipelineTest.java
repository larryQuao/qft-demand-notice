package com.qft.demandnotice.service;

import com.qft.demandnotice.model.DemandNoticeData;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the Publisher template flow: create a real .pub via
 * Publisher COM, convert it to PDF, stamp the token placeholders and verify
 * the values. Skipped automatically on machines without Microsoft Publisher.
 */
class PublisherPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void pubTemplate_isConvertedAndStamped() throws Exception {
        Assumptions.assumeTrue(publisherInstalled(),
                "Microsoft Publisher not installed - skipping integration test");
        Assumptions.assumeTrue(!publisherRunning(),
                "Microsoft Publisher is currently running - skipping to avoid interfering "
                        + "with the interactive session");

        Path pub = tempDir.resolve("template.pub");
        Path converted = tempDir.resolve("template.pdf");
        Path finalOut = tempDir.resolve("Demand_Notice_NorthCode_September2026.pdf");

        createProbePub(pub);
        PubToPdfConverter.convert(pub, converted);

        DemandNoticeData data = new DemandNoticeData(
                LocalDate.of(2026, Month.SEPTEMBER, 24),
                YearMonth.of(2026, Month.SEPTEMBER),
                new BigDecimal("1389.00"),
                LocalDate.of(2026, Month.OCTOBER, 14));

        TemplateStamper.StampOutcome outcome =
                TemplateStamper.stamp(Files.readAllBytes(converted), data);
        Files.write(finalOut, outcome.pdfBytes());

        assertTrue(outcome.replacedTokens().containsAll(List.of(
                "{{LETTER_DATE}}", "{{PERIOD}}", "{{AMOUNT}}", "{{DEADLINE}}")));

        String text;
        try (PDDocument doc = Loader.loadPDF(finalOut.toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("GHS 1,389.00"), "stamped amount missing:\n" + text);
        assertTrue(text.contains("24th September, 2026"), "stamped letter date missing:\n" + text);
        assertTrue(text.contains("September, 2026"), "stamped period missing:\n" + text);
        assertTrue(text.contains("14th October, 2026"), "stamped deadline missing:\n" + text);
        assertFalse(text.contains("{{AMOUNT}}"), "token not replaced:\n" + text);

        // Convenience copy for manual inspection next to the project.
        Path inspect = Path.of("target", "inspect");
        Files.createDirectories(inspect);
        Files.copy(finalOut, inspect.resolve(finalOut.getFileName()),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * End-to-end test of the auto-fill flow {@link TemplateStamper#prepare}:
     * the .pub is a filled-in letter (no tokens), so placeholders must be
     * recognised, auto-inserted into a copy, and stamped with fresh values.
     */
    @Test
    void filledLetter_isAutoTokenizedAndStamped() throws Exception {
        Assumptions.assumeTrue(publisherInstalled(),
                "Microsoft Publisher not installed - skipping integration test");
        Assumptions.assumeTrue(!publisherRunning(),
                "Microsoft Publisher is currently running - skipping to avoid interfering "
                        + "with the interactive session");

        Path pub = tempDir.resolve("filled.pub");
        Path finalOut = tempDir.resolve("Demand_Notice_Filled_September2026.pdf");

        createFilledPub(pub);

        DemandNoticeData data = new DemandNoticeData(
                LocalDate.of(2026, Month.NOVEMBER, 3),
                YearMonth.of(2026, Month.OCTOBER),
                new BigDecimal("4120.50"),
                LocalDate.of(2026, Month.NOVEMBER, 17));

        // The original .pub must not be touched by prepare().
        byte[] pubBefore = Files.readAllBytes(pub);

        TemplateStamper.PreparedTemplate prepared = TemplateStamper.prepare(pub);
        assertTrue(prepared.hasTokens(), "auto-tokenize found no tokens");
        assertTrue(prepared.autoInserted().containsAll(List.of(
                "AMOUNT", "DEADLINE", "PERIOD", "LETTER_DATE")),
                "not all fields auto-recognized: " + prepared.autoInserted());
        assertTrue(prepared.missingTokens().isEmpty(),
                "unexpectedly missing tokens: " + prepared.missingTokens());
        assertTrue(java.util.Arrays.equals(pubBefore, Files.readAllBytes(pub)),
                "prepare() modified the original .pub");

        TemplateStamper.StampOutcome outcome = prepared.stamp(data);
        Files.write(finalOut, outcome.pdfBytes());
        assertTrue(outcome.replacedTokens().containsAll(List.of(
                "{{LETTER_DATE}}", "{{PERIOD}}", "{{AMOUNT}}", "{{DEADLINE}}")));

        String text;
        try (PDDocument doc = Loader.loadPDF(finalOut.toFile())) {
            text = new PDFTextStripper().getText(doc);
        }
        assertTrue(text.contains("GHS 4,120.50"), "stamped amount missing:\n" + text);
        assertTrue(text.contains("3rd November, 2026"), "stamped letter date missing:\n" + text);
        assertTrue(text.contains("October, 2026"), "stamped period missing:\n" + text);
        assertTrue(text.contains("17th November, 2026"), "stamped deadline missing:\n" + text);
        assertFalse(text.contains("GHS 2,345.16"),
                "the old hard-coded amount survived the stamping:\n" + text);
        assertFalse(text.contains("{{"), "token not replaced:\n" + text);
    }

    // ------------------------------------------------------------------

    private static boolean publisherInstalled() {
        String[] candidates = {
                System.getenv("ProgramFiles") + "/Microsoft Office/root/Office16/MSPUB.EXE",
                System.getenv("ProgramFiles(x86)") + "/Microsoft Office/root/Office16/MSPUB.EXE",
                System.getenv("ProgramFiles") + "/Microsoft Office/Office16/MSPUB.EXE",
                System.getenv("ProgramFiles") + "/Microsoft Office/Office15/MSPUB.EXE"
        };
        for (String candidate : candidates) {
            if (candidate != null && Files.isRegularFile(Path.of(candidate))) {
                return true;
            }
        }
        return false;
    }

    private static boolean publisherRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "@(Get-Process MSPUB -ErrorAction SilentlyContinue).Count");
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(30, TimeUnit.SECONDS);
            return !out.isEmpty() && !out.equals("0");
        } catch (Exception e) {
            return false; // if we cannot tell, let the test try
        }
    }

    private static void createProbePub(Path out) throws Exception {
        try (var in = PublisherPipelineTest.class.getResourceAsStream("/scripts/create_probe_pub.ps1")) {
            Files.write(out.resolveSibling("create_probe_pub.ps1"), in.readAllBytes());
        }
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", out.resolveSibling("create_probe_pub.ps1").toAbsolutePath().toString(),
                "-OutPath", out.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(240, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
        }
        Assumptions.assumeTrue(finished && process.exitValue() == 0 && Files.isRegularFile(out),
                "Could not create probe .pub via Publisher COM:\n" + output);
    }

    private static void createFilledPub(Path out) throws Exception {
        try (var in = PublisherPipelineTest.class.getResourceAsStream("/scripts/create_filled_pub.ps1")) {
            Files.write(out.resolveSibling("create_filled_pub.ps1"), in.readAllBytes());
        }
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", out.resolveSibling("create_filled_pub.ps1").toAbsolutePath().toString(),
                "-OutPath", out.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean finished = process.waitFor(240, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
        }
        Assumptions.assumeTrue(finished && process.exitValue() == 0 && Files.isRegularFile(out),
                "Could not create filled .pub via Publisher COM:\n" + output);
    }
}
