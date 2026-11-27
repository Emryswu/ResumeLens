package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.repository.AppUserRepository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Shared package-private helpers for user-scoped services that lock the owning user row,
 * run short programmatic transactions, and normalize query or metadata strings.
 */
final class UserOwnedServiceSupport {
    private UserOwnedServiceSupport() {
    }

    static AppUser lockUser(AppUserRepository appUserRepository, Long userId) {
        return appUserRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));
    }

    static <T> T inTransaction(TransactionTemplate transactionTemplate, Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    static String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
