package com.cafecli.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HookPayload 必须能干净地序列化成 JSON（后续 shell 租户 slice 依赖这个形状）。
 */
class HookPayloadTest {

    @Test
    void stopPayloadSerializesToJson() throws Exception {
        HookPayload payload = HookPayload.forStop("sess-1", null, "/proj",
                List.of(new HookPayload.TurnTool("write_file", "Foo.java", true),
                        new HookPayload.TurnTool("execute_command", "mvn test", true)),
                1);

        String json = new ObjectMapper().writeValueAsString(payload);

        assertTrue(json.contains("\"event\":\"STOP\""), json);
        assertTrue(json.contains("\"projectRoot\":\"/proj\""), json);
        assertTrue(json.contains("\"blockCount\":1"), json);
        assertTrue(json.contains("write_file"), json);
        assertTrue(json.contains("mvn test"), json);
    }
}
