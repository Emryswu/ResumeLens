package com.arthur.jdragresume.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsAuthAndAnalysisThrottlesTo429() {
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                handler.handleBusiness(new BusinessException("AUTH_RATE_LIMITED", "slow down")).getStatusCode()
        );
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                handler.handleBusiness(new BusinessException("ANALYSIS_RATE_LIMITED", "slow down")).getStatusCode()
        );
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                handler.handleBusiness(new BusinessException("ANALYSIS_TOO_MANY_PENDING", "wait")).getStatusCode()
        );
    }

    @Test
    void mapsDuplicatePendingAnalysisTo409() {
        assertEquals(
                HttpStatus.CONFLICT,
                handler.handleBusiness(new BusinessException("ANALYSIS_ALREADY_PENDING", "running")).getStatusCode()
        );
    }

    @Test
    void mapsStaleSemanticEmbeddingsTo409() {
        assertEquals(
                HttpStatus.CONFLICT,
                handler.handleBusiness(
                        new BusinessException("SEMANTIC_EMBEDDING_STALE", "refresh required")
                ).getStatusCode()
        );
    }

    @Test
    void mapsAgentApprovalMismatchTo409() {
        assertEquals(
                HttpStatus.CONFLICT,
                handler.handleBusiness(
                        new BusinessException("AGENT_APPROVAL_MISMATCH", "answer the pending action first")
                ).getStatusCode()
        );
    }

    @Test
    void accountConflictMatchesTheUniqueConstraintPath() {
        // 串行注册走应用层查重，并发注册走 uk_app_user_username / uk_app_user_email，
        // 是同一件事的两条路径，状态码必须一致
        HttpStatusCode viaApplicationCheck =
                handler.handleBusiness(new BusinessException("ACCOUNT_CONFLICT", "taken")).getStatusCode();
        HttpStatusCode viaUniqueConstraint =
                handler.handleDataIntegrity(new DataIntegrityViolationException("duplicate key")).getStatusCode();

        assertEquals(HttpStatus.CONFLICT, viaApplicationCheck);
        assertEquals(viaUniqueConstraint, viaApplicationCheck);
    }

    @Test
    void mapsFullAnalysisQueueTo503() {
        // 消息是 "please retry later"，状态码若为 400 会让重试与熔断策略失去依据
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                handler.handleBusiness(new BusinessException("ANALYSIS_QUEUE_FULL", "queue is full")).getStatusCode()
        );
    }

    @Test
    void mapsEmbeddingFailuresTo503() {
        // 语义粗排与 RAG 共用同一套本地 embedding；瞬时失败都应让客户端稍后重试，
        // 而不是落到 default 400 被当成请求错误。
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                handler.handleBusiness(new BusinessException("RAG_EMBEDDING_FAILED", "embed failed")).getStatusCode()
        );
        assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                handler.handleBusiness(
                        new BusinessException("SEMANTIC_EMBEDDING_FAILED", "semantic ranking embedding is temporarily unavailable")
                ).getStatusCode()
        );
    }

    @Test
    void mapsFileSaveFailureTo500() {
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleBusiness(new BusinessException("FILE_SAVE_FAILED", "disk is full")).getStatusCode()
        );
    }

    @Test
    void mapsUnsupportedHttpMethodTo405() {
        assertEquals(
                HttpStatus.METHOD_NOT_ALLOWED,
                handler.handleMethodNotAllowed(new HttpRequestMethodNotSupportedException("PUT")).getStatusCode()
        );
    }

    @Test
    void stillMapsGenuineClientErrorsTo400() {
        assertEquals(
                HttpStatus.BAD_REQUEST,
                handler.handleBusiness(new BusinessException("UNSUPPORTED_FILE_TYPE", "nope")).getStatusCode()
        );
        assertEquals(
                HttpStatus.BAD_REQUEST,
                handler.handleBusiness(new BusinessException("RESUME_TEXT_EMPTY", "empty")).getStatusCode()
        );
    }
}
