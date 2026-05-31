package com.pooli.traffic.service.invoke;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.service.outbox.TrafficInFlightDedupeDeleteOutboxService;

import lombok.RequiredArgsConstructor;

/**
 * 정상 차감 완료 시 done log와 dedupe delete outbox 적재를 같은 DB 트랜잭션 안에서 처리합니다.
 *
 * <p>consumer는 이 서비스를 통해 커밋되어야 할 영속 변경만 완료하고,
 * Redis dedupe key 즉시 삭제와 stream ACK 같은 후속 처리는 트랜잭션 커밋 이후 단계에서 수행합니다.</p>
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductCompletionPersistenceService {

    private final TrafficDeductDoneLogService trafficDeductDoneLogService;
    private final TrafficInFlightDedupeDeleteOutboxService trafficInFlightDedupeDeleteOutboxService;

    /**
     * 정상 데이터 차감 완료 후 반드시 함께 보존되어야 하는 DB 변경을 하나의 트랜잭션으로 묶습니다.
     *
     * <p>done log 저장이 실패하면 outbox PENDING 레코드를 만들지 않고 예외를 전파합니다.
     * done log가 이미 존재하는 중복 완료인 경우에도 기존 consumer 정책을 유지하기 위해
     * dedupe delete outbox PENDING 레코드는 생성합니다.</p>
     *
     * @return done log 신규 저장 여부와 생성된 dedupe delete outbox ID
     */
    @Transactional
    public CompletionPersistenceResult persistCompletion(
            TrafficPayloadReqDto payload,
            TrafficDeductResultResDto result,
            String recordId,
            Long latency
    ) {
        // [정상 경로]
         boolean saved = trafficDeductDoneLogService.saveIfAbsent(payload, result, recordId, latency);
         long outboxId = trafficInFlightDedupeDeleteOutboxService.createPendingDeferred(
                 payload.getTraceId(),
                 recordId
         );
         return new CompletionPersistenceResult(saved, outboxId);

        // [DB Insert 비활성화] done log INSERT + outbox PENDING 생성을 모두 건너뜁니다.
//        return new CompletionPersistenceResult(true, -1L);
    }

    public record CompletionPersistenceResult(boolean saved, long outboxId) {
    }
}
