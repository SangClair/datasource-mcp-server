package com.dameng.mcp.util;

import com.dameng.mcp.model.mcp.ToolException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class CursorCodec {

    private static final String VERSION = "v1:";

    private CursorCodec() {
    }

    public static String encode(String value) {
        if (value == null) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((VERSION + value).getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String cursor, String defaultValue) {
        if (cursor == null || cursor.isBlank()) {
            return defaultValue;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(VERSION)) {
                throw new IllegalArgumentException("unsupported cursor version");
            }
            return decoded.substring(VERSION.length());
        } catch (IllegalArgumentException e) {
            throw new ToolException("INVALID_ARGUMENT", "cursor 无效或版本不受支持");
        }
    }

    public static int decodeOffset(String cursor) {
        String decoded = decode(cursor, "0");
        try {
            int offset = Integer.parseInt(decoded);
            if (offset < 0) {
                throw new NumberFormatException("negative offset");
            }
            return offset;
        } catch (NumberFormatException e) {
            throw new ToolException("INVALID_ARGUMENT", "cursor 中的分页位置无效");
        }
    }
}
