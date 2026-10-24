package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;

/** Both browsers reserve from the same persistent daily counter before any BOSS navigation. */
@Service
public class BossPageBudget {
    public static final int DAILY_LIMIT = 30;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public BossPageBudget(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Grant reserve(CollectionSession session, String key, int pages) {
        return transactions.execute(status -> {
            LocalDate date = LocalDate.now(clock);
            jdbc.update("INSERT IGNORE INTO boss_daily_budget(user_id,budget_date,pages) VALUES(?,?,0)", session.userId(), date);
            int used = jdbc.queryForObject("SELECT pages FROM boss_daily_budget WHERE user_id=? AND budget_date=? FOR UPDATE",
                    Integer.class, session.userId(), date);
            var previous = jdbc.queryForList("SELECT reserved_pages,budget_date,used_pages FROM boss_page_reservation WHERE reservation_id=? AND user_id=?", key, session.userId());
            if (!previous.isEmpty()) {
                var reservation = previous.getFirst();
                if (reservation.get("used_pages") != null || !reservation.get("budget_date").toString().equals(date.toString())) {
                    throw new BusinessException("JOB_SEARCH_BUDGET", "本次页数预留已结束或跨日，请发起新的采集");
                }
                return new Grant(key, ((Number) reservation.get("reserved_pages")).intValue(), DAILY_LIMIT - used, date);
            }
            if (pages > DAILY_LIMIT - used) throw new BusinessException("JOB_SEARCH_BUDGET", "今天的 BOSS 页数额度不足，两种采集方式共用每日 30 页上限");
            jdbc.update("UPDATE boss_daily_budget SET pages=pages+? WHERE user_id=? AND budget_date=?", pages, session.userId(), date);
            jdbc.update("INSERT INTO boss_page_reservation(reservation_id,user_id,budget_date,reserved_pages) VALUES(?,?,?,?)",
                    key, session.userId(), date, pages);
            return new Grant(key, pages, DAILY_LIMIT - used - pages, date);
        });
    }

    /** Unknown/abandoned tasks retain their reservation; only a definite report can release unused pages. */
    public int finish(CollectionSession session, String key, Integer pagesLoaded) {
        return transactions.execute(status -> {
            var identity = jdbc.queryForMap("SELECT user_id,budget_date FROM boss_page_reservation WHERE reservation_id=? AND user_id=?", key, session.userId());
            jdbc.queryForObject("SELECT pages FROM boss_daily_budget WHERE user_id=? AND budget_date=? FOR UPDATE",
                    Integer.class, session.userId(), identity.get("budget_date"));
            var row = jdbc.queryForMap("SELECT * FROM boss_page_reservation WHERE reservation_id=? AND user_id=? FOR UPDATE", key, session.userId());
            int reserved = ((Number) row.get("reserved_pages")).intValue();
            if (pagesLoaded != null && (pagesLoaded < 0 || pagesLoaded > reserved)) {
                throw new BusinessException("BROWSER_REPORT_INVALID", "浏览器返回的实际加载页数超出本次额度");
            }
            if (row.get("used_pages") == null) {
                int actual = pagesLoaded == null ? reserved : pagesLoaded;
                jdbc.update("UPDATE boss_page_reservation SET used_pages=? WHERE reservation_id=?", actual, key);
                jdbc.update("UPDATE boss_daily_budget SET pages=pages-? WHERE user_id=? AND budget_date=?",
                        reserved - actual, session.userId(), row.get("budget_date"));
            }
            return DAILY_LIMIT - jdbc.queryForObject("SELECT pages FROM boss_daily_budget WHERE user_id=? AND budget_date=?",
                    Integer.class, session.userId(), row.get("budget_date"));
        });
    }

    public record Grant(String key, int pages, int dailyPagesLeft, LocalDate date) {}
}
