package com.arthur.jdragresume.exception;

import com.arthur.jdragresume.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "REFRESH_TOKEN_INVALID", "REFRESH_TOKEN_EXPIRED", "REFRESH_TOKEN_REUSED" -> HttpStatus.UNAUTHORIZED;
            case "AUTH_RATE_LIMITED", "ANALYSIS_RATE_LIMITED", "ANALYSIS_TOO_MANY_PENDING",
                 "AGENT_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
            // ACCOUNT_CONFLICT 必须与 DataIntegrityViolationException 同为 409：
            // 串行注册被应用层查重拦下、并发注册被唯一约束拦下，是同一件事的两条路径。
            // ANALYSIS_DELETE_PENDING 同理：请求本身合法，但与当前资源状态冲突。
            case "ANALYSIS_ALREADY_PENDING", "ANALYSIS_DELETE_PENDING", "ACCOUNT_CONFLICT",
                 "SEMANTIC_EMBEDDING_STALE" -> HttpStatus.CONFLICT;
            case "AI_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            // 队列满是服务端暂时容纳不下，客户端应当稍后重试；落到 400 会与
            // "please retry later" 的提示自相矛盾，也让重试与熔断策略失去依据。
            case "AI_NOT_CONFIGURED", "AI_RATE_LIMITED", "AI_SERVICE_UNAVAILABLE",
                 "RAG_EMBEDDING_FAILED", "SEMANTIC_EMBEDDING_FAILED",
                 "RAG_RETRIEVAL_FAILED", "ANALYSIS_QUEUE_FULL", "AGENT_BUSY" -> HttpStatus.SERVICE_UNAVAILABLE;
            // 落盘失败是服务端故障，不是请求有问题
            case "FILE_SAVE_FAILED" -> HttpStatus.INTERNAL_SERVER_ERROR;
            case "AI_AUTH_FAILED", "AI_BALANCE_INSUFFICIENT", "AI_REQUEST_FAILED",
                 "AI_REQUEST_ERROR", "AI_REQUEST_INTERRUPTED", "AI_RESPONSE_EMPTY",
                 "AI_RESPONSE_PARSE_FAILED", "AI_RESPONSE_CITATION_INVALID" -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("BAD_CREDENTIALS", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", "request validation failed", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("METHOD_NOT_ALLOWED", ex.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("DATA_INTEGRITY_ERROR", "data conflicts with existing records or constraints"));
    }

    // 并发修改同一行（典型场景：语义向量刷新期间用户又编辑了简历/岗位）。
    // 与 DataIntegrityViolationException 同为 409：都是「请求本身没错，但与当前
    // 数据状态冲突」，客户端重新拉取后重试即可。
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "CONCURRENT_MODIFICATION",
                        "record was modified by another request, please reload and retry"
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnhandled(Exception ex) {
        log.error("Unhandled server exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "unexpected server error"));
    }
}
