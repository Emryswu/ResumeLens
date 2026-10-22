package com.arthur.jdragresume.agent;

/**
 * Tool results for a browser task that did not deliver a report. Each says plainly what happened
 * and that nothing was saved, so the model can tell the user instead of guessing.
 */
final class BrowserTaskOutcomes {
    static final String EXPIRED = "BROWSER_TASK_EXPIRED";
    static final String UNKNOWN = "BROWSER_TASK_UNKNOWN";
    static final String CANCELLED = "BROWSER_TASK_CANCELLED";
    static final String ABANDONED = "BROWSER_TASK_ABANDONED";

    private BrowserTaskOutcomes() {
    }

    static String expired(String tool, long timeoutSeconds) {
        return ToolEnvelope.error(tool, EXPIRED, "等待浏览器返回采集结果超过 " + timeoutSeconds
                + " 秒，这次采集已作废，结果没有保存。告诉用户超时了，可以检查插件后重新发起。");
    }

    static String unknown(String tool) {
        return ToolEnvelope.error(tool, UNKNOWN,
                "服务端没有在等待这次采集（可能已经超时作废、被新的采集替换，或服务重启过），结果没有保存。");
    }

    static String cancelled(String tool) {
        return ToolEnvelope.error(tool, CANCELLED, "用户取消了这次采集，没有保存任何岗位。不要自动重新发起。");
    }

    static String abandoned(String tool) {
        return ToolEnvelope.error(tool, ABANDONED, "用户没有等采集完成就继续提问，这次采集的结果不会使用，没有保存任何岗位。");
    }

    /** The result for an open browser call the user left behind, by what the registry knew about it. */
    static String left(BrowserTaskRegistry.Claim claim, String tool, long timeoutSeconds) {
        return switch (claim.state()) {
            case ACTIVE -> abandoned(tool);
            case EXPIRED -> expired(tool, timeoutSeconds);
            case UNKNOWN -> unknown(tool);
        };
    }
}
