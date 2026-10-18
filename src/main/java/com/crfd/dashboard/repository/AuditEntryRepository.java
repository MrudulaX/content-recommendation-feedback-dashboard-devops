package com.crfd.dashboard.repository;

import com.crfd.dashboard.domain.AuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEntryRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findByEventRefOrderByCreatedAtAscIdAsc(String eventRef);

    List<AuditEntry> findByEventRefInOrderByCreatedAtAscIdAsc(List<String> eventRefs);
}
