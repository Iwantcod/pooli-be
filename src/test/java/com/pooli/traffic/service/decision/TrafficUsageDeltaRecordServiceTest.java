package com.pooli.traffic.service.decision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.entity.TrafficRedisUsageDeltaRecord;
import com.pooli.traffic.domain.enums.TrafficPoolType;
import com.pooli.traffic.mapper.TrafficRedisUsageDeltaMapper;

@ExtendWith(MockitoExtension.class)
class TrafficUsageDeltaRecordServiceTest {

    @Mock
    private TrafficRedisUsageDeltaMapper trafficRedisUsageDeltaMapper;

    @InjectMocks
    private TrafficUsageDeltaRecordService trafficUsageDeltaRecordService;

    @Test
    @DisplayName("유효한 fallback 결과를 usage delta로 저장한다")
    void recordsUsageDeltaWhenInputIsValid() {
        // when
        trafficUsageDeltaRecordService.record(
                "trace-001",
                TrafficPoolType.INDIVIDUAL,
                11L,
                22L,
                33,
                100L,
                LocalDate.of(2026, 3, 16),
                YearMonth.of(2026, 3)
        );

        // then
        verify(trafficRedisUsageDeltaMapper).insertIgnoreDuplicate(any(TrafficRedisUsageDeltaRecord.class));
    }

    @Test
    @DisplayName("유효하지 않은 입력은 usage delta를 저장하지 않는다")
    void skipsRecordWhenInputIsInvalid() {
        // when
        trafficUsageDeltaRecordService.record(
                "",
                TrafficPoolType.INDIVIDUAL,
                11L,
                22L,
                33,
                100L,
                LocalDate.of(2026, 3, 16),
                YearMonth.of(2026, 3)
        );

        // then
        verify(trafficRedisUsageDeltaMapper, never()).insertIgnoreDuplicate(any(TrafficRedisUsageDeltaRecord.class));
    }

    @Test
    @DisplayName("replay 후보 선점 시 PROCESSING 상태로 전이한다")
    void marksProcessingWhenLockingReplayCandidates() {
        // given
        TrafficRedisUsageDeltaRecord candidate = TrafficRedisUsageDeltaRecord.builder().id(7L).build();
        when(trafficRedisUsageDeltaMapper.selectReplayCandidatesForUpdate(3, 30))
                .thenReturn(List.of(candidate));

        // when
        trafficUsageDeltaRecordService.lockReplayCandidatesAndMarkProcessing(3, 30);

        // then
        verify(trafficRedisUsageDeltaMapper).markProcessing(eq(7L));
    }
}
