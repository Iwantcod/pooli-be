package com.pooli.traffic.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficRestoreLuaContractTest {

    @Test
    @DisplayName("restore replay Lua는 idempotency key 존재 시 사용량과 잔량을 변경하지 않는다")
    void skipsReplayWhenIdempotencyKeyExists() throws IOException {
        String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

        assertThat(lua).contains("redis.call('EXISTS', idempotency_key)");
        assertThat(lua).contains("return { 'SKIPPED' }");
    }

    @Test
    @DisplayName("restore Lua 타입은 replay와 correction 스크립트를 등록한다")
    void registersRestoreLuaScriptTypes() throws IOException {
        String enumSource = Files.readString(Path.of("src/main/java/com/pooli/traffic/domain/enums/TrafficLuaScriptType.java"));

        assertThat(enumSource).contains("RESTORE_USAGE_REPLAY(\"restore_usage_replay\", \"lua/traffic/restore_usage_replay.lua\")");
        assertThat(enumSource).contains("RESTORE_USAGE_CORRECTION(\"restore_usage_correction\", \"lua/traffic/restore_usage_correction.lua\")");
    }
}
