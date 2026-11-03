package com.documind.document;

import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface DeletionJournalRepository extends Repository<DeletionJournalEntry, UUID> {
    DeletionJournalEntry save(DeletionJournalEntry entry);
}
