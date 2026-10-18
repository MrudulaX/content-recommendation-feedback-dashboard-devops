package com.crfd.dashboard.repository;

import com.crfd.dashboard.domain.Alert;
import com.crfd.dashboard.domain.AlertCode;
import com.crfd.dashboard.domain.AlertSeverity;
import com.crfd.dashboard.domain.AlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    Optional<Alert> findByAlertRef(String alertRef);

    List<Alert> findByEventRefOrderByCreatedAtDesc(String eventRef);

    long countByStatus(AlertStatus status);

    long countByStatusAndSeverity(AlertStatus status, AlertSeverity severity);

    long countByCode(AlertCode code);

    /** Guards against raising the same alert twice for the same event while it is still open. */
    boolean existsByEventRefAndCodeAndStatus(String eventRef, AlertCode code, AlertStatus status);

    /** Timestamps for the alert overlay of the dashboard time series. */
    @Query("select a.createdAt from Alert a where a.createdAt >= :from")
    List<Instant> findCreatedAtSince(@Param("from") Instant from);
}
