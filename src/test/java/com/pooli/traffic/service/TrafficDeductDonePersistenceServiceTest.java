package com.pooli.traffic.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDone;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.mapper.TrafficDeductDoneMapper;

@ExtendWith(MockitoExtension.class)
class TrafficDeductDonePersistenceServiceTest {

    @Mock
    private TrafficDeductDoneMapper trafficDeductDoneMapper;

    @InjectMocks
    private TrafficDeductDonePersistenceService trafficDeductDonePersistenceService;

    @Nested
    @DisplayName("existsByTraceId 테스트")
    class ExistsByTraceIdTest {

        @Test
        @DisplayName("빈 traceId면 false 반환")
        void blankTraceIdReturnsFalse() {
            // when
            boolean exists = trafficDeductDonePersistenceService.existsByTraceId(" ");

            // then
            assertFalse(exists);
        }

        @Test
        @DisplayName("유효 traceId면 mapper 조회 결과 반환")
        void validTraceIdReturnsMapperResult() {
            // given
            when(trafficDeductDoneMapper.existsByTraceId("trace-001")).thenReturn(true);

            // when
            boolean exists = trafficDeductDonePersistenceService.existsByTraceId("trace-001");

            // then
            assertTrue(exists);
            verify(trafficDeductDoneMapper).existsByTraceId("trace-001");
        }
    }

    @Nested
    @DisplayName("saveIfAbsent 테스트")
    class SaveIfAbsentTest {

        @Test
        @DisplayName("insertIgnore 1건이면 신규 저장 성공(true)")
        void returnsTrueWhenInserted() {
            // given
            when(trafficDeductDoneMapper.insertIgnore(org.mockito.ArgumentMatchers.any(TrafficDeductDone.class)))
                    .thenReturn(1);

            // when
            boolean saved = trafficDeductDonePersistenceService.saveIfAbsent(payload(), result());

            // then
            assertTrue(saved);
        }

        @Test
        @DisplayName("insertIgnore 0건이면 중복 처리(false)")
        void returnsFalseWhenDuplicated() {
            // given
            when(trafficDeductDoneMapper.insertIgnore(org.mockito.ArgumentMatchers.any(TrafficDeductDone.class)))
                    .thenReturn(0);

            // when
            boolean saved = trafficDeductDonePersistenceService.saveIfAbsent(payload(), result());

            // then
            assertFalse(saved);
        }
    }

    private TrafficPayloadReqDto payload() {
        return TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(System.currentTimeMillis())
                .build();
    }

    private TrafficDeductResultResDto result() {
        return TrafficDeductResultResDto.builder()
                .traceId("trace-001")
                .apiTotalData(100L)
                .deductedTotalBytes(90L)
                .apiRemainingData(10L)
                .finalStatus(TrafficFinalStatus.PARTIAL_SUCCESS)
                .lastLuaStatus(TrafficLuaStatus.NO_BALANCE)
                .createdAt(LocalDateTime.now().minusSeconds(1))
                .finishedAt(LocalDateTime.now())
                .build();
    }
}
