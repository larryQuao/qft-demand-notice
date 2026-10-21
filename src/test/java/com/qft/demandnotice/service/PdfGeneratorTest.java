package com.qft.demandnotice.service;

import com.qft.demandnotice.model.DemandNoticeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates a PDF using the exact values from the plan's sample
 * (24th September, 2026 / September, 2026 / GHS 1,389.00 / 14th October, 2026)
 * so the output can be compared visually against the original sample letter.
 */
class PdfGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void generate_producesPdfWithSampleValues() throws Exception {
        DemandNoticeData data = new DemandNoticeData(
                LocalDate.of(2026, Month.SEPTEMBER, 24),
                YearMonth.of(2026, Month.SEPTEMBER),
                new BigDecimal("1389.00"),
                LocalDate.of(2026, Month.OCTOBER, 14));

        Path out = tempDir.resolve("Demand_Notice_NorthCode_September2026.pdf");
        PdfGenerator.generate(data, out);

        assertTrue(Files.exists(out), "PDF was not created");
        assertTrue(Files.size(out) > 0, "PDF is empty");

        // Convenience copy for manual inspection next to the project.
        Path inspect = Path.of("target", "inspect");
        Files.createDirectories(inspect);
        Files.copy(out, inspect.resolve(out.getFileName()),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
