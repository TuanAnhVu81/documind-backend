package com.documind.document.storage;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileAlreadyExistsException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements FileStorage {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path root;
    private final JdbcClient jdbc;
    private final boolean initializeIfEmpty;
    private static final String MARKER = ".documind-storage-id";

    public LocalFileStorage(@Value("${documind.storage.path:./uploads}") String path, JdbcClient jdbc,
                            @Value("${documind.storage.initialize-if-empty:false}") boolean initializeIfEmpty) {
        root = Path.of(path).toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.initializeIfEmpty = initializeIfEmpty;
    }

    @Override
    public StoredFile store(InputStream input, String filename) throws IOException {
        String extension = extension(filename);
        prepareStorage();
        String key = UUID.randomUUID().toString();
        Path path = resolve(key);
        boolean created = false;
        try {
            long total = 0;
            var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            created = true;
            try (output) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_BYTES) throw new AppException(ErrorCode.FILE_TOO_LARGE);
                    output.write(buffer, 0, count);
                }
            }
            if (total == 0) throw new AppException(ErrorCode.INVALID_REQUEST);
            return new StoredFile(key, total, detect(path, extension));
        } catch (IOException | RuntimeException failure) {
            if (created) try { Files.deleteIfExists(path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static String extension(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/")
                || filename.contains("\\") || filename.contains("..") || filename.chars().anyMatch(Character::isISOControl))
            throw new AppException(ErrorCode.INVALID_REQUEST);
        int dot = filename.lastIndexOf('.');
        if (dot < 1) throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String detect(Path path, String extension) throws IOException {
        byte[] bytes = Files.readAllBytes(path); // bounded to 10 MiB; no document extraction
        if (extension.equals("pdf") && looksLikePdf(bytes)) return "application/pdf";
        if (extension.equals("docx") && bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K') {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (hasXmlRoot(zip, "[Content_Types].xml", "Types", "http://schemas.openxmlformats.org/package/2006/content-types")
                        && hasXmlRoot(zip, "word/document.xml", "document", "http://schemas.openxmlformats.org/wordprocessingml/2006/main"))
                    return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            } catch (ZipException invalid) { throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE); }
        }
        if (extension.equals("txt")) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (text.codePoints().noneMatch(c -> c == 0 || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')))
                    return "text/plain";
            } catch (CharacterCodingException invalid) { /* invalid text */ }
        }
        throw new AppException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    private static boolean looksLikePdf(byte[] bytes) {
        if (bytes.length < 20 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return false;
        String tail = new String(bytes, Math.max(0, bytes.length - 2048), Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1);
        int start = tail.lastIndexOf("startxref");
        if (start < 0) return false;
        var match = java.util.regex.Pattern.compile("startxref\\s+(\\d+)\\s+%%EOF\\s*").matcher(tail.substring(start));
        if (!match.matches()) return false;
        try {
            int offset = Integer.parseInt(match.group(1));
            if (offset < 0 || offset >= bytes.length) return false;
            String target = new String(bytes, offset, Math.min(80, bytes.length - offset), StandardCharsets.ISO_8859_1);
            return target.startsWith("xref") || target.matches("(?s)\\d+\\s+\\d+\\s+obj\\b.*");
        } catch (NumberFormatException invalid) { return false; }
    }

    private static boolean hasXmlRoot(ZipFile zip, String name, String local, String namespace) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) return false;
        // Read at most 64 KiB decompressed, only until the first element; do not parse document content.
        try (var input = zip.getInputStream(entry)) {
            byte[] prefix = input.readNBytes(65536);
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
            var reader = factory.createXMLStreamReader(new java.io.ByteArrayInputStream(prefix));
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT)
                        return local.equals(reader.getLocalName()) && namespace.equals(reader.getNamespaceURI());
                }
            } finally { reader.close(); }
        } catch (XMLStreamException invalid) { return false; }
        return false;
    }

    private String expectedIdentity() {
        return jdbc.sql("SELECT storage_id FROM document_storage_identity WHERE singleton=true")
                .query(UUID.class).single().toString();
    }

    private synchronized void prepareStorage() throws IOException {
        String expected = expectedIdentity();
        Path marker = root.resolve(MARKER);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!initializeIfEmpty) throw new IOException("Storage marker missing; initialize the verified volume first");
            long referenced = jdbc.sql("SELECT (SELECT count(*) FROM documents) + (SELECT count(*) FROM deletion_journal_outbox)")
                    .query(Long.class).single();
            if (referenced != 0) throw new IOException("Storage marker missing; refusing to initialize existing storage");
            Files.createDirectories(root);
            try (var files = Files.list(root)) {
                if (files.findAny().isPresent()) {
                    if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) { verifyStorage(expected); return; }
                    throw new IOException("Storage directory is not empty");
                }
            }
            try { Files.writeString(marker, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
            catch (FileAlreadyExistsException concurrent) { /* another initializer */ }
        }
        verifyStorage(expected);
    }

    private void verifyStorage(String expected) throws IOException {
        Path marker = root.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Storage marker unavailable");
        try (var in = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            String actual = new String(in.readNBytes(128), StandardCharsets.US_ASCII);
            if (!expected.equals(actual)) throw new IOException("Storage identity mismatch");
        }
    }

    private void verifyStorage() throws IOException { verifyStorage(expectedIdentity()); }

    private Path resolve(String key) {
        if (key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid storage key");
        return root.resolve(key);
    }

    @Override public InputStream open(String key) throws IOException { verifyStorage(); return Files.newInputStream(resolve(key), LinkOption.NOFOLLOW_LINKS); }
    @Override public void delete(String key) throws IOException { verifyStorage(); Files.deleteIfExists(resolve(key)); }

    @Override public List<Entry> entries() throws IOException {
        verifyStorage();
        try (var files = Files.list(root)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    .map(path -> {
                        try { return new Entry(path.getFileName().toString(), Files.getLastModifiedTime(path).toInstant()); }
                        catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                    }).toList();
        } catch (java.io.UncheckedIOException exception) { throw exception.getCause(); }
    }
}
