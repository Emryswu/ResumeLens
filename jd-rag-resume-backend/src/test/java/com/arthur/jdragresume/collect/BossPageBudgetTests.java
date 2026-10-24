package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossPageBudgetTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T17:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private final CollectionSession session = new CollectionSession(1L, "42", "task", "call", CollectorMode.AUTO, Long.MAX_VALUE, event -> {});

    private BossPageBudget budget() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), any(LocalDate.class))).thenReturn(0);
        return new BossPageBudget(jdbc, manager, clock);
    }

    @Test void reservationUsesTheShanghaiDateAfterMidnightEvenWhenUtcIsStillYesterday() {
        var grant = budget().reserve(session, "reservation", 3);
        var date = LocalDate.of(2026, 10, 25);
        assertEquals(date, grant.date());
        assertEquals(27, grant.dailyPagesLeft());
        verify(jdbc).update("INSERT INTO boss_page_reservation(reservation_id,user_id,budget_date,reserved_pages) VALUES(?,?,?,?)",
                "reservation", 1L, date, 3);
    }

    @Test void aReservationFromThePreviousShanghaiDayIsRejected() {
        var budget = budget();
        when(jdbc.queryForList(anyString(), eq("yesterday"), eq(1L))).thenReturn(List.of(
                Map.of("reserved_pages", 3, "budget_date", LocalDate.of(2026, 10, 24))));
        assertEquals("JOB_SEARCH_BUDGET", assertThrows(BusinessException.class,
                () -> budget.reserve(session, "yesterday", 3)).getCode());
    }
}
