package com.bookmarked.auth.history;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LoginHistoryRepository extends JpaRepository<LoginHistory, Long> {

    List<LoginHistory> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);

    boolean existsByUserIdAndSuccessTrue(Long userId);

    boolean existsByUserIdAndSuccessTrueAndUserAgent(Long userId, String userAgent);
}
