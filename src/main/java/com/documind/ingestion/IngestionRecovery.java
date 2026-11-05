package com.documind.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovery remains active even when processing is disabled. */
@Component
public class IngestionRecovery {
    private static final Logger log = LoggerFactory.getLogger(IngestionRecovery.class);
    private final IngestionQueue queue;

    public IngestionRecovery(IngestionQueue queue) { this.queue = queue; }

    @Scheduled(fixedDelayString = "${documind.ingestion.recovery-ms:30000}")
    public void recover() {
        try { queue.recoverDue(); }
        catch (RuntimeException failure) { log.error("Ingestion recovery failed type={}", failure.getClass().getName()); }
    }
}
