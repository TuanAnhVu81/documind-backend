package com.documind.chat;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Recovers abandoned generations without allowing a late worker to finalize them. */
@Component
public class GenerationRecovery {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final boolean scheduledEnabled;

    public GenerationRecovery(JdbcClient jdbc, PlatformTransactionManager manager,
            @Value("${documind.chat.recovery-enabled:true}") boolean scheduledEnabled) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.scheduledEnabled = scheduledEnabled;
    }

    @Scheduled(fixedDelayString = "${documind.chat.recovery-ms:30000}", initialDelayString = "${documind.chat.recovery-ms:30000}")
    public void scheduledRecover() { if (scheduledEnabled) recover(); }

    public void recover() {
        tx.executeWithoutResult(status -> {
            var messages = jdbc.sql("""
                    WITH stale AS (
                      SELECT id FROM chat_generations WHERE status='GENERATING'
                        AND coalesce(heartbeat_at,created_at) < now() - interval '60 seconds'
                      FOR UPDATE SKIP LOCKED
                    )
                    UPDATE chat_generations g SET status='INCOMPLETE',outcome='TIMEOUT',generation_version=g.generation_version+1
                    FROM stale WHERE g.id=stale.id AND g.status='GENERATING' RETURNING g.assistant_message_id
                    """).query(UUID.class).list();
            if (!messages.isEmpty()) jdbc.sql("""
                    UPDATE messages SET status='INCOMPLETE',outcome='TIMEOUT',sources='[]'::jsonb
                    WHERE id IN (:ids) AND status='GENERATING'
                    """).param("ids", messages).update();
        });
    }
}
