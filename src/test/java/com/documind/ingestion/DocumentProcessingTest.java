package com.documind.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class DocumentProcessingTest {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test void chunksLongSupplementaryUnicodeWithoutLosingOffsets() {
        String text = "😀".repeat(100_000);
        var extracted = new DocumentExtractor.Extracted(List.of(new DocumentExtractor.Section(text, null, 1)), null, 1);
        var parts = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> new DocumentChunker(1000, 150, 5000).split(extracted));
        assertThat(parts).hasSize(118);
        for (var part : parts) {
            assertThat(part.text()).isEqualTo(text.substring(part.start() * 2, part.end() * 2));
        }
        assertThat(parts.getLast().end()).isEqualTo(100_000);
    }

    @Test void rejectsAggregateExpandedDocxBeforePoiParsing() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            byte[] block = new byte[8192];
            for (int entry = 0; entry < 2; entry++) {
                zip.putNextEntry(new ZipEntry("part" + entry));
                for (int i = 0; i < 2049; i++) zip.write(block);
                zip.closeEntry();
            }
        }
        assertThat(bytes.size()).isLessThan(10 * 1024 * 1024);
        assertResourceLimit(bytes.toByteArray());
    }

    @Test void rejectsTooManyDocxEntriesBeforePoiParsing() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < 1001; i++) {
                zip.putNextEntry(new ZipEntry("entry" + i));
                zip.write(1);
                zip.closeEntry();
            }
        }
        assertResourceLimit(bytes.toByteArray());
    }

    private static void assertResourceLimit(byte[] zip) {
        assertThatThrownBy(() -> new DocumentExtractor(200, 1_000_000).extract(new ByteArrayInputStream(zip), DOCX))
                .isInstanceOf(DocumentExtractor.ExtractionFailure.class)
                .extracting(e -> ((DocumentExtractor.ExtractionFailure) e).code()).isEqualTo("DOCX_RESOURCE_LIMIT");
    }
}
