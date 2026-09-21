package com.dameng.mcp.util;

import com.dameng.mcp.model.mcp.ToolException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    @Test
    void roundTripsOpaqueCursor() {
        String cursor = CursorCodec.encode("127");
        assertThat(cursor).doesNotContain("127");
        assertThat(CursorCodec.decodeOffset(cursor)).isEqualTo(127);
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> CursorCodec.decodeOffset("not-base64!"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("cursor");
    }
}
