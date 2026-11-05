package com.documind.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DocumentExtractor {
    private static final long MAX_DOCX_EXPANDED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DOCX_ENTRIES = 1000;
    public record Section(String text, Integer page, Integer paragraph) {}
    public record Extracted(List<Section> sections, Integer pages, Integer paragraphs) {}
    private final int maxPages, maxCodePoints;

    public DocumentExtractor(@Value("${documind.parser.max-pdf-pages:200}") int maxPages,
                             @Value("${documind.parser.max-code-points:1000000}") int maxCodePoints) {
        if (maxPages < 1 || maxCodePoints < 1) throw new IllegalArgumentException("Invalid parser limits");
        this.maxPages = maxPages; this.maxCodePoints = maxCodePoints;
    }

    public Extracted extract(InputStream input, String mediaType) throws IOException, ExtractionFailure, InterruptedException {
        List<Section> sections = new ArrayList<>();
        long[] total = {0};
        Integer pages = null, paragraphs = null;
        byte[] file = input.readNBytes(10 * 1024 * 1024 + 1);
        if (file.length > 10 * 1024 * 1024) throw new ExtractionFailure("INVALID_DOCUMENT");
        if ("application/pdf".equals(mediaType)) {
            try (var pdf = Loader.loadPDF(file)) {
                pages = pdf.getNumberOfPages();
                if (pages > maxPages) throw new ExtractionFailure("PDF_PAGE_LIMIT");
                var stripper = new PDFTextStripper();
                for (int page = 1; page <= pages; page++) {
                    interrupted(); stripper.setStartPage(page); stripper.setEndPage(page);
                    var writer = new BoundedWriter(2L * maxCodePoints + 2);
                    stripper.writeText(pdf, writer);
                    add(sections, writer.text.toString(), page, null, total);
                }
            } catch (TextLimitIOException e) { throw new ExtractionFailure("TEXT_LIMIT"); }
            catch (ExtractionFailure | InterruptedException e) { throw e; }
            catch (IOException | RuntimeException e) { throw new ExtractionFailure("INVALID_DOCUMENT"); }
        } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mediaType)) {
            // Check actual expanded bytes across all central-directory entries before POI builds an object graph.
            try {
                validateDocxArchive(file);
                try (var doc = new XWPFDocument(new java.io.ByteArrayInputStream(file))) {
                    int[] count = {0};
                    extractBody(doc.getBodyElements(), sections, total, count, 0);
                    paragraphs = count[0];
                }
            } catch (ExtractionFailure | InterruptedException e) { throw e; }
            catch (IOException | RuntimeException e) { throw new ExtractionFailure("INVALID_DOCUMENT"); }
        } else if ("text/plain".equals(mediaType)) {
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file)).toString(); }
            catch (java.nio.charset.CharacterCodingException invalid) { throw new ExtractionFailure("INVALID_TEXT_ENCODING"); }
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            int count = 0;
            try (var lines = new java.io.BufferedReader(new java.io.StringReader(text))) {
                String line;
                while ((line = lines.readLine()) != null) { interrupted(); add(sections, line, null, ++count, total); }
            }
            paragraphs = count;
        } else throw new ExtractionFailure("UNSUPPORTED_DOCUMENT");
        if (sections.isEmpty()) throw new ExtractionFailure("NO_EXTRACTABLE_TEXT");
        return new Extracted(List.copyOf(sections), pages, paragraphs);
    }

    private static void validateDocxArchive(byte[] file) throws IOException, ExtractionFailure, InterruptedException {
        long total = 0;
        int count = 0;
        var names = new HashSet<String>();
        try (var zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(file)).get()) {
            var entries = zip.getEntries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                interrupted();
                var entry = entries.nextElement();
                if (++count > MAX_DOCX_ENTRIES) throw new ExtractionFailure("DOCX_RESOURCE_LIMIT");
                if (!names.add(entry.getName())) throw new ExtractionFailure("INVALID_DOCUMENT");
                try (var contents = zip.getInputStream(entry)) {
                    int read;
                    while ((read = contents.read(buffer)) != -1) {
                        interrupted();
                        total += read;
                        if (total > MAX_DOCX_EXPANDED_BYTES) throw new ExtractionFailure("DOCX_RESOURCE_LIMIT");
                    }
                }
            }
        }
        // POI opens an InputStream, so also bound its sequential view of local ZIP entries.
        total = 0;
        count = 0;
        names.clear();
        try (var zip = new ZipArchiveInputStream(new java.io.ByteArrayInputStream(file))) {
            byte[] buffer = new byte[8192];
            org.apache.commons.compress.archivers.zip.ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                interrupted();
                if (++count > MAX_DOCX_ENTRIES) throw new ExtractionFailure("DOCX_RESOURCE_LIMIT");
                if (!names.add(entry.getName())) throw new ExtractionFailure("INVALID_DOCUMENT");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    interrupted();
                    total += read;
                    if (total > MAX_DOCX_EXPANDED_BYTES) throw new ExtractionFailure("DOCX_RESOURCE_LIMIT");
                }
            }
        }
    }

    private void extractBody(List<IBodyElement> body, List<Section> sections, long[] total, int[] count, int depth)
            throws ExtractionFailure, InterruptedException {
        if (depth > 32) throw new ExtractionFailure("INVALID_DOCUMENT");
        for (IBodyElement element : body) {
            interrupted();
            if (element instanceof XWPFParagraph paragraph) {
                count[0]++;
                add(sections, paragraph.getText(), null, count[0], total);
            } else if (element instanceof XWPFTable table) {
                for (var row : table.getRows()) for (var cell : row.getTableCells())
                    extractBody(cell.getBodyElements(), sections, total, count, depth + 1);
            }
        }
    }

    private void add(List<Section> sections, String input, Integer page, Integer paragraph, long[] total) throws ExtractionFailure {
        String normalized = Normalizer.normalize(input.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC).strip();
        if (normalized.isEmpty()) return;
        total[0] += normalized.codePointCount(0, normalized.length());
        if (total[0] > maxCodePoints) throw new ExtractionFailure("TEXT_LIMIT");
        sections.add(new Section(normalized, page, paragraph));
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Extraction interrupted");
    }

    private static final class TextLimitIOException extends IOException { }

    private static final class BoundedWriter extends java.io.Writer {
        private final StringBuilder text = new StringBuilder();
        private final long maxChars;
        private BoundedWriter(long maxChars) { this.maxChars = maxChars; }
        @Override public void write(char[] buffer, int offset, int length) throws IOException {
            if ((long) text.length() + length > maxChars) throw new TextLimitIOException();
            text.append(buffer, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    public static final class ExtractionFailure extends Exception {
        private final String code;
        public ExtractionFailure(String code) { super("Document extraction failed"); this.code = code; }
        public String code() { return code; }
    }
}
