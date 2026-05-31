package com.pooli.traffic.service.restore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.traffic.mapper.LineDailyBatchJobMapper;

@ExtendWith(MockitoExtension.class)
class TrafficRestoreStartDateResolverTest {

    @Mock
    private LineDailyBatchJobMapper batchJobMapper;

    @Test
    @DisplayName("복구 시작일은 장애일 이하의 마지막 완료 일별 동기화 다음 날로 계산한다")
    void resolvesNextDayAfterLastCompletedUsageSyncBatch() {
        LocalDate failureDate = LocalDate.of(2026, 5, 29);
        TrafficRestoreStartDateResolver resolver = new TrafficRestoreStartDateResolver(batchJobMapper);
        when(batchJobMapper.selectLatestCompletedUsageSyncDateOnOrBefore(failureDate))
                .thenReturn(LocalDate.of(2026, 5, 26));

        LocalDate restoreStartDate = resolver.resolve(failureDate);

        assertThat(restoreStartDate).isEqualTo(LocalDate.of(2026, 5, 27));
    }

    @Test
    @DisplayName("완료된 일별 동기화 이력이 없으면 장애일 당일 done log만 복구하도록 장애일을 시작일로 사용한다")
    void usesFailureDateWhenCompletedUsageSyncBatchDoesNotExist() {
        LocalDate failureDate = LocalDate.of(2026, 5, 29);
        TrafficRestoreStartDateResolver resolver = new TrafficRestoreStartDateResolver(batchJobMapper);
        when(batchJobMapper.selectLatestCompletedUsageSyncDateOnOrBefore(failureDate)).thenReturn(null);

        LocalDate restoreStartDate = resolver.resolve(failureDate);

        assertThat(restoreStartDate).isEqualTo(failureDate);
    }
}
