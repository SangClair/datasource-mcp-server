package com.dameng.mcp.web;

import com.dameng.mcp.config.DataSourceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 访问令牌过滤器（仅 http 模式生效）。
 * <p>
 * 当配置了 {@code mcp.web.access-token} 时，对以下接口强制校验令牌：
 * <ul>
 *     <li>{@code /mcp}、{@code /mcp/**}：MCP 传输端点（STREAMABLE 模式下真正执行
 *         数据库工具调用的入口）。STREAMABLE 为单端点协议，客户端每个请求都会携带
 *         配置的 {@code X-Access-Token} 头，故可安全纳入校验，不存在旧 SSE 双端点
 *         模式下后续消息请求不带头导致握手超时的问题。</li>
 *     <li>{@code /api/**}：数据源管理接口（新增/删除数据源、变更连接凭据等）。</li>
 * </ul>
 * 校验失败返回 401。令牌仅从请求头 {@code X-Access-Token} 读取（不再支持查询参数，
 * 避免令牌出现在访问日志中），并使用 {@link MessageDigest#isEqual} 做常数时间比较，
 * 规避字符串逐字符比较可能带来的时序侧信道。
 * </p>
 * <p>
 * 静态管理页面（{@code /}、{@code /index.html} 等）放行——它只是一个不含机密的壳，
 * 页面内的数据访问请求仍受本过滤器保护。未配置令牌时本过滤器不做任何拦截，保持默认开放。
 * </p>
 */
@Slf4j
@Profile("http")
@Component
@Order(1)
public class AccessTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_HEADER = "X-Access-Token";

    private final DataSourceProperties properties;

    public AccessTokenFilter(DataSourceProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = properties.getWeb() == null ? null : properties.getWeb().getAccessToken();
        // 未配置令牌：保持默认开放，不做任何拦截
        if (!StringUtils.hasText(token)) {
            chain.doFilter(request, response);
            return;
        }
        if (isProtected(request) && !tokenMatches(request, token)) {
            log.warn("拒绝未授权访问: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"访问被拒绝：缺少或错误的访问令牌(" + TOKEN_HEADER + ")\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 需要保护的路径：
     * <ul>
     *     <li>{@code /mcp}、{@code /mcp/**}：MCP 传输端点（STREAMABLE 模式下的工具调用入口）。</li>
     *     <li>{@code /api/**}：数据源管理接口（新增/删除数据源、变更连接凭据等）。</li>
     * </ul>
     * STREAMABLE 为单端点协议，客户端每个请求都会带上 {@code X-Access-Token} 头，
     * 因此可对传输端点强制校验，不会出现旧 SSE 双端点模式下后续消息请求不带头而握手超时的问题。
     */
    private boolean isProtected(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/api/")
                || uri.equals("/mcp")
                || uri.startsWith("/mcp/");
    }

    /**
     * 校验请求头中的令牌，使用常数时间比较避免时序侧信道。
     */
    private boolean tokenMatches(HttpServletRequest request, String token) {
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
