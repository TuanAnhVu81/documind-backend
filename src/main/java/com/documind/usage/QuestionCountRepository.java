package com.documind.usage;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Counts accepted generations independently of provider usage and deleted conversations. */
@Repository
public class QuestionCountRepository {
    private final JdbcClient jdbc;

    public QuestionCountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long countByUserId(UUID userId) {
        if (userId == null) throw new IllegalArgumentException("User ID is required");
        return jdbc.sql("SELECT count(*) FROM question_events WHERE user_id = :userId")
                .param("userId", userId).query(Long.class).single();
    }
}
