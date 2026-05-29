package com.pooli.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * app.traffic.restore.* 복구 batch 설정값을 바인딩한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.traffic.restore")
public class TrafficRestoreProperties {

    /** worker가 한 번에 claim할 target row 수 */
    private int workerChunkSize = 5000;

    /** PROCESSING lease가 만료됐다고 판단할 초 단위 기준 */
    private int processingLeaseTimeoutSeconds = 300;

    /** 자동 재시도 후 FAILED로 전환할 retry 한도 */
    private int retryLimit = 3;

    /** 장애 복구 진행 flag로 사용하는 POLICY row 식별자 */
    private int restorePolicyId = 8;
}
