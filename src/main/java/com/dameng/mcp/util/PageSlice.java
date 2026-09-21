package com.dameng.mcp.util;

import java.util.List;

public record PageSlice<T>(List<T> items, int returnedCount, boolean hasMore, String nextCursor) {

    public static <T> PageSlice<T> of(List<T> source, int offset, int limit) {
        List<T> safe = source == null ? List.of() : source;
        int from = Math.min(offset, safe.size());
        int to = Math.min(from + limit, safe.size());
        boolean hasMore = to < safe.size();
        return new PageSlice<>(List.copyOf(safe.subList(from, to)), to - from, hasMore,
                hasMore ? CursorCodec.encode(Integer.toString(to)) : null);
    }
}
