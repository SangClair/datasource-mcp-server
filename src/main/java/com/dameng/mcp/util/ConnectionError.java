package com.dameng.mcp.util;

/**
 * 连接类错误识别与文案生成工具。
 * <p>
 * 用于区分「数据库连接/认证层面失败」（如账号密码错误、数据库不可达）与「SQL 执行失败」。
 * 连接类失败会反复消耗连接尝试、放大账号锁定风险，因此各 Tool 服务检测到此类错误时，
 * 直接向调用方（agent）返回友好且明确的提示，并说明已停止重试。
 * </p>
 */
public final class ConnectionError {

    private ConnectionError() {
    }

    /**
     * 判断异常链中是否包含数据库连接 / 认证层面的失败。
     *
     * @param t 原始异常（可为 null）
     * @return true 表示属于连接类失败
     */
    public static boolean isConnectionFailure(Throwable t) {
        String msg = collectMessages(t);
        if (msg == null || msg.isEmpty()) {
            return false;
        }
        msg = msg.toLowerCase();
        String[] keys = {
                // 认证 / 密码
                "password", "authentication", "login failed", "access denied",
                "verified failed", "username or password", "用户名或密码", "登录失败", "验证失败", "认证失败",
                "sa/dba 登录失败", "dn driver", "账号或密码",
                // 连接 / 网络
                "connect", "could not create connection", "connection refused",
                "connection reset", "network adapter", "communicateexception",
                "cannot connect", "连接失败", "连接被拒绝", "无法连接", "无法连接到", "建立连接", "创建连接"
        };
        for (String key : keys) {
            if (msg.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成返回给调用方（agent）的连接类错误提示。
     *
     * @param t          底层异常
     * @param datasource 数据源名称（用于定位问题归属）
     * @return 面向 agent 的友好错误信息
     */
    public static String describe(Throwable t, String datasource) {
        return "数据源 [" + datasource + "] 连接失败（可能为账号/密码错误或数据库不可达，而非 SQL 本身的问题）。"
                + "为避免反复尝试连接导致账号被锁定，本服务已停止对该数据源的重试。"
                + "请先检查并修复该数据源的账号、密码或连接地址后，再重新发起调用。"
                + "具体错误：" + rootMessage(t);
    }

    /**
     * 提取最深层（最具体）的错误消息，作为关键细节返回。
     */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        if (m == null || m.isEmpty()) {
            m = t.getMessage();
        }
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /**
     * 拼接整条异常链消息（小写），用于关键词匹配。深度上限防止循环引用。
     */
    private static String collectMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        collectMessages(t, sb, 0);
        return sb.toString();
    }

    private static void collectMessages(Throwable t, StringBuilder sb, int depth) {
        if (t == null || depth > 8) {
            return;
        }
        if (t.getMessage() != null) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t.getMessage());
        }
        collectMessages(t.getCause(), sb, depth + 1);
    }
}