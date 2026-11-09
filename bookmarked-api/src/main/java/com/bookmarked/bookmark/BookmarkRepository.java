package com.bookmarked.bookmark;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    List<Bookmark> findByUserIdOrderByAddedAtDesc(Long userId);

    Optional<Bookmark> findByUserIdAndBook_Id(Long userId, Long bookId);

    long countByUserId(Long userId);

    @Query("select b.book.id from Bookmark b where b.userId = :userId")
    List<Long> findBookIds(@Param("userId") Long userId);
}
