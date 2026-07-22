package com.dameng.mcp.web;

import com.dameng.mcp.config.DataSourceProperties;
import com.dameng.mcp.model.DataSourceInfo;
import com.dameng.mcp.service.DataSourceManager;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据源管理 REST 接口（仅 http 模式生效）。
 * <p>
 * 提供数据源的查询、动态新增、删除与连接测试能力，供内置 Web 管理页面调用。
 * 当配置了 {@code mcp.web.access-token} 时，所有接口需携带请求头 {@code X-Access-Token}。
 * </p>
 */
@Slf4j
@Profile("http")
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private static final String TOKEN_HEADER = "X-Access-Token";

    private final DataSourceManager manager;
    private final DataSourceProperties properties;

    public DataSourceController(DataSourceManager manager, DataSourceProperties properties) {
        this.manager = manager;
        this.properties = properties;
    }

    /**
     * 列出所有已注册数据源（不含密码）。
     */
    @GetMapping
    public ApiResponse<List<DataSourceInfo>> list(HttpServletRequest request) {
        String denied = checkToken(request);
        if (denied != null) {
            return ApiResponse.fail(denied);
        }
        try {
            return ApiResponse.ok("查询成功", manager.list());
        } catch (Exception e) {
            log.error("查询数据源列表失败", e);
            return ApiResponse.fail("查询失败：" + safeMessage(e));
        }
    }

    /**
     * 动态新增数据源。
     */
    @PostMapping
    public ApiResponse<Void> add(@RequestBody DataSourceProperties.DataSourceItem item, HttpServletRequest request) {
        String denied = checkToken(request);
        if (denied != null) {
            return ApiResponse.fail(denied);
        }
        try {
            manager.add(item);
            return ApiResponse.ok("数据源 [" + item.getName() + "] 添加成功");
        } catch (IllegalArgumentException iae) {
            return ApiResponse.fail(safeMessage(iae));
        } catch (Exception e) {
            log.error("新增数据源失败", e);
            return ApiResponse.fail("新增失败：" + safeMessage(e));
        }
    }

    /**
     * 删除动态数据源。
     */
    @DeleteMapping("/{name}")
    public ApiResponse<Void> remove(@PathVariable("name") String name, HttpServletRequest request) {
        String denied = checkToken(request);
        if (denied != null) {
            return ApiResponse.fail(denied);
        }
        try {
            manager.remove(name);
            return ApiResponse.ok("数据源 [" + name + "] 已删除");
        } catch (IllegalArgumentException iae) {
            return ApiResponse.fail(safeMessage(iae));
        } catch (Exception e) {
            log.error("删除数据源失败：name={}", name, e);
            return ApiResponse.fail("删除失败：" + safeMessage(e));
        }
    }

    /**
     * 测试数据源连接（不保存）。
     */
    @PostMapping("/test")
    public ApiResponse<Void> test(@RequestBody DataSourceProperties.DataSourceItem item, HttpServletRequest request) {
        String denied = checkToken(request);
        if (denied != null) {
            return ApiResponse.fail(denied);
        }
        try {
            manager.testConnection(item);
            return ApiResponse.ok("连接成功");
        } catch (IllegalArgumentException iae) {
            return ApiResponse.fail(safeMessage(iae));
        } catch (Exception e) {
            return ApiResponse.fail("连接失败：" + safeMessage(e));
        }
    }

    /**
     * 校验访问令牌。返回 null 表示通过，否则返回错误信息。
     */
    private String checkToken(HttpServletRequest request) {
        String token = properties.getWeb() == null ? null : properties.getWeb().getAccessToken();
        if (!StringUtils.hasText(token)) {
            // 未配置令牌，跳过校验
            return null;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (!token.equals(provided)) {
            return "访问被拒绝：缺少或错误的访问令牌(" + TOKEN_HEADER + ")";
        }
        return null;
    }

    private String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
