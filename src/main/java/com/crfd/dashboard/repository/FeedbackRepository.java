package com.crfd.dashboard.repository;

import com.crfd.dashboard.domain.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    Page<Feedback> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Feedback> findByItemIdOrderByCreatedAtDesc(String itemId, Pageable pageable);

    @Query("select avg(f.rating) from Feedback f")
    Double averageRating();

    @Query("select avg(f.rating) from Feedback f where f.createdAt >= :from")
    Double averageRatingSince(@Param("from") Instant from);

    @Query("select avg(f.rating) from Feedback f where f.itemId = :itemId")
    Double averageRatingForItem(@Param("itemId") String itemId);

    long countByItemId(String itemId);

    long countByCreatedAtAfter(Instant from);
}
