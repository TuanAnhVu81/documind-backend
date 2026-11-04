package com.documind.document;

import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService documents;
    public DocumentController(DocumentService documents) { this.documents = documents; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentService.UploadResult upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) throws IOException {
        return documents.upload(UUID.fromString(jwt.getSubject()), file);
    }

    @GetMapping
    public DocumentService.DocumentPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return documents.list(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping("/{id}")
    public DocumentService.DocumentDto detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return documents.detail(UUID.fromString(jwt.getSubject()), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        documents.requestDeletion(UUID.fromString(jwt.getSubject()), id);
    }
}
