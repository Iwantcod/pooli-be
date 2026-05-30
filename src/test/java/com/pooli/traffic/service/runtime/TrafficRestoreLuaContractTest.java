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
    @DisplayName("restore replay Lua는 daily total usage를 string counter로 복구한다")
    void restoreReplayUsesStringCounterForDailyTotalUsage() throws IOException {
        String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

        assertThat(lua).contains("redis.call('INCRBY', KEYS[4], total_usage)");
        assertThat(lua).doesNotContain("redis.call('HINCRBY', KEYS[4], 'individual'");
        assertThat(lua).doesNotContain("redis.call('HINCRBY', KEYS[4], 'shared'");
        assertThat(lua).doesNotContain("redis.call('HINCRBY', KEYS[4], 'qos'");
    }

    @Test
    @DisplayName("restore replay Lua는 공유 사용량이 양수일 때만 가족풀 사용량 hash를 생성한다")
    void restoreReplayCreatesSharedUsageHashOnlyForPositiveSharedUsage() throws IOException {
        String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_replay.lua"));

        assertThat(lua).contains("if shared_usage > 0 then");
        assertThat(lua).contains("redis.call('HINCRBY', KEYS[6], 'usage_amount', shared_usage)");
        assertThat(lua).contains("redis.call('HSET', KEYS[6], 'family_id', family_id)");
        assertThat(lua).contains("redis.call('HINCRBY', KEYS[7], 'usage_amount', shared_usage)");
        assertThat(lua).contains("redis.call('HSET', KEYS[7], 'family_id', family_id)");
    }

    @Test
    @DisplayName("restore correction Lua는 string value와 hash field 보정을 구분한다")
    void restoreCorrectionSupportsStringAndHashCorrection() throws IOException {
        String lua = Files.readString(Path.of("src/main/resources/lua/traffic/restore_usage_correction.lua"));

        assertThat(lua).contains("local value_kind = ARGV[1]");
        assertThat(lua).contains("if value_kind == 'string' then");
        assertThat(lua).contains("redis.call('SET', KEYS[1], ARGV[3])");
        assertThat(lua).contains("if value_kind == 'hash' then");
        assertThat(lua).contains("redis.call('HSET', KEYS[1], ARGV[2], ARGV[3])");
    }

    @Test
    @DisplayName("restore Lua 타입은 replay와 correction 스크립트를 등록한다")
    void registersRestoreLuaScriptTypes() throws IOException {
        String enumSource = Files.readString(Path.of("src/main/java/com/pooli/traffic/domain/enums/TrafficLuaScriptType.java"));

        assertThat(enumSource).contains("RESTORE_USAGE_REPLAY(\"restore_usage_replay\", \"lua/traffic/restore_usage_replay.lua\")");
        assertThat(enumSource).contains("RESTORE_USAGE_CORRECTION(\"restore_usage_correction\", \"lua/traffic/restore_usage_correction.lua\")");
    }
}
