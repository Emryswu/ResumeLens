package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** One durable row per user, including completed collections that returned no listings. */
@Service
public class JobCollectionHistory {
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;

    public JobCollectionHistory(JdbcTemplate jdbc, CurrentUserService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public void record(CollectRequest request, CollectionResult result) {
        LocalDateTime completedAt = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO latest_job_collection
                    (user_id, keyword, city, experience, max_pages, pages_read, status, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE keyword = ?, city = ?, experience = ?, max_pages = ?,
                    pages_read = ?, status = ?, completed_at = ?
                """, users.getCurrentUser().getId(), request.keyword(), request.city(), request.experience(),
                request.maxPages(), result.pagesRead(), result.outcome().name(), completedAt,
                request.keyword(), request.city(), request.experience(), request.maxPages(), result.pagesRead(),
                result.outcome().name(), completedAt);
    }

    @Transactional(readOnly = true)
    public Optional<Latest> latest() {
        return jdbc.query("""
                SELECT keyword, city, experience, max_pages, pages_read, status, completed_at
                FROM latest_job_collection WHERE user_id = ?
                """, (row, index) -> new Latest("BOSS", row.getString("keyword"), row.getString("city"),
                row.getString("experience"), row.getInt("max_pages"), row.getInt("pages_read"),
                row.getString("status"), row.getTimestamp("completed_at").toLocalDateTime()),
                users.getCurrentUser().getId()).stream().findFirst();
    }

    public record Latest(String platform, String keyword, String city, String experience, int maxPages,
                         int pagesRead, String status, LocalDateTime completedAt) { }
}
