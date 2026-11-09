package com.documind.chat.service;

import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import java.util.UUID;

public final class ChatConflict extends AppException {
    private final UUID generationId;
    private final UUID assistantMessageId;
    private final String generationStatus;

    public ChatConflict(ErrorCode code, UUID generationId, UUID assistantMessageId, String generationStatus) {
        super(code); this.generationId = generationId; this.assistantMessageId = assistantMessageId; this.generationStatus = generationStatus;
    }
    public UUID generationId() { return generationId; }
    public UUID assistantMessageId() { return assistantMessageId; }
    public String generationStatus() { return generationStatus; }
}
