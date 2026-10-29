package com.arthur.jdragresume.exception;

/**
 * A tool refused a call before doing any work: no browser task was issued and no page was charged.
 * The agent loop records it as not executed, so the job-search budget does not count it and a retry is allowed.
 */
public class ToolNotStartedException extends BusinessException {
    public ToolNotStartedException(String code, String message) {
        super(code, message);
    }
}
