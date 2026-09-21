package com.dameng.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.tools")
public class McpToolProperties {

    private int defaultPageSize = 100;
    private int maxPageSize = 500;
    private int maxItemChars = 4096;
    private int maxOutputBytes = 65536;

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = positive(defaultPageSize, "default-page-size");
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = positive(maxPageSize, "max-page-size");
    }

    public int getMaxItemChars() {
        return maxItemChars;
    }

    public void setMaxItemChars(int maxItemChars) {
        this.maxItemChars = positive(maxItemChars, "max-item-chars");
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = positive(maxOutputBytes, "max-output-bytes");
    }

    public int normalizeLimit(Integer requested) {
        if (requested != null && requested <= 0) {
            throw new IllegalArgumentException("limit 必须为正整数");
        }
        int value = requested == null ? defaultPageSize : requested;
        return Math.min(value, maxPageSize);
    }

    private int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException("mcp.tools." + name + " must be positive");
        }
        return value;
    }
}
