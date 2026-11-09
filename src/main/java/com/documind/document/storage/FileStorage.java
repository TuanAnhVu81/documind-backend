package com.documind.document.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

public interface FileStorage {
    record StoredFile(String key, long size, String mediaType) {}
    record Entry(String key, Instant modifiedAt) {}
    StoredFile store(InputStream input, String filename) throws IOException;
    InputStream open(String key) throws IOException;
    void delete(String key) throws IOException;
    List<Entry> entries() throws IOException;
}
