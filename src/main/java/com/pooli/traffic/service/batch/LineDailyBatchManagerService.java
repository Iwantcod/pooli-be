package com.pooli.traffic.service.batch;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 일별 사용량 동기화 batch의 manager 역할 진입점이다.
 */
@Slf4j
@Service
@Profile({"local", "traffic"})
public class LineDailyBatchManagerService {

    public void run(LocalDate usageDate, String managerInstanceId) {
        log.info(
                "line_daily_batch_manager_selected usageDate={} managerInstanceId={}",
                usageDate,
                managerInstanceId
        );
    }
}
