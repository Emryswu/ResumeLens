package com.arthur.jdragresume.common;

import com.arthur.jdragresume.exception.BusinessException;

/**
 * Describes an exception for logs without its message: type, business error code, the first stack frame in our
 * own code, and the cause's type. Messages can carry resume text or contact details, so they never reach a log line.
 */
public final class LogSafe {
    private static final String OWN_PACKAGE = "com.arthur.";
    private static final int MAX_CAUSES = 3;

    private LogSafe() { }

    public static String describe(Throwable ex) {
        if (ex == null) return "type=null";
        StringBuilder out = new StringBuilder("type=").append(ex.getClass().getSimpleName());
        if (ex instanceof BusinessException business && business.getCode() != null) {
            out.append(" code=").append(business.getCode());
        }
        out.append(" at=").append(ownFrame(ex));
        Throwable cause = ex.getCause();
        for (int depth = 0; cause != null && cause != ex && depth < MAX_CAUSES; depth++) {
            out.append(" cause=").append(cause.getClass().getSimpleName());
            cause = cause.getCause();
        }
        return out.toString();
    }

    /** The first frame in our own code, where the failure meets the application; JDK and library frames say little. */
    private static String ownFrame(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith(OWN_PACKAGE)) {
                    String className = frame.getClassName();
                    return className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName()
                            + ":" + frame.getLineNumber();
                }
            }
            if (current.getCause() == current) break;
        }
        return "unknown";
    }
}
