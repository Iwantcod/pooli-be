package com.pooli.traffic.service.invoke;

import java.time.LocalDateTime;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;
import com.pooli.traffic.domain.dto.response.TrafficDeductResultResDto;
import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;
import com.pooli.traffic.domain.enums.TrafficFinalStatus;
import com.pooli.traffic.domain.enums.TrafficLuaStatus;
import com.pooli.traffic.mapper.DoneLogInsertOperation;
import com.pooli.traffic.mapper.TrafficDeductDoneLogMapper;
import com.pooli.traffic.util.TrafficRetryBackoffSupport;

import lombok.RequiredArgsConstructor;

/**
 * 트래픽 차감 완료 로그를 MySQL에 저장하고 중복(traceId) 여부를 조회하는 서비스입니다.
 */
@Service
@Profile({"local", "traffic"})
@RequiredArgsConstructor
public class TrafficDeductDoneLogService {

    private static final int DONE_LOG_DB_RETRY_MAX = 3;
    private static final long DONE_LOG_DB_RETRY_BASE_MS = 50L;
    private static final int RESTORE_LAST_ERROR_MESSAGE_MAX_LENGTH = 1000;

    private final TrafficDeductDoneLogMapper trafficDeductDoneLogMapper;

    /**
     * traceId 완료 이력이 존재하는지 확인합니다.
     */
    public boolean existsByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        return trafficDeductDoneLogMapper.existsByTraceId(traceId);
    }

    /**
     * 완료 로그를 신규로 저장합니다.
     *
     * @return 신규 저장이면 true, traceId 중복이면 false
     */
    public boolean saveIfAbsent(
            TrafficPayloadReqDto payload,
            TrafficDeductResultResDto result,
            String recordId,
            Long latency
    ) {
        if (payload == null || result == null) {
            throw new IllegalArgumentException("payload/result must not be null");
        }
        if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }

        LocalDateTime startedAt = defaultNowIfNull(result.getCreatedAt());
        TrafficDeductDoneLog doneLog = TrafficDeductDoneLog.builder()
                .traceId(payload.getTraceId())
                .recordId(recordId)
                .lineId(payload.getLineId())
                .familyId(payload.getFamilyId())
                .appId(payload.getAppId())
                .apiTotalData(result.getApiTotalData())
                .deductedTotalBytes(result.getDeductedTotalBytes())
                .apiRemainingData(result.getApiRemainingData())
                .finalStatus(result.getFinalStatus() == null ? null : result.getFinalStatus().name())
                .lastLuaStatus(result.getLastLuaStatus() == null ? null : result.getLastLuaStatus().name())
                .startedAt(startedAt)
                .finishedAt(defaultNowIfNull(result.getFinishedAt()))
                .latency(normalizeLatency(latency))
                .build();

        // saveWithRetry는 "재시도 공통 흐름"만 담당하고,
        // 실제 INSERT SQL 선택은 메서드 레퍼런스로 주입합니다.
        return saveWithRetry(doneLog, trafficDeductDoneLogMapper::insert);
    }

    /**
     * traceId를 확보한 non-retryable 예외 종결 로그를 저장합니다.
     *
     * @return 신규 저장이면 true, traceId 중복이면 false
     */
    public boolean saveNonRetryableFailureIfAbsent(
            TrafficPayloadReqDto payload,
            String recordId,
            Long latency,
            String restoreLastErrorMessage
    ) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (payload.getTraceId() == null || payload.getTraceId().isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }

        long apiTotalData = normalizeNonNegative(payload.getApiTotalData());
        LocalDateTime now = LocalDateTime.now();
        TrafficDeductDoneLog doneLog = TrafficDeductDoneLog.builder()
                .traceId(payload.getTraceId())
                .recordId(recordId)
                .lineId(payload.getLineId())
                .familyId(payload.getFamilyId())
                .appId(payload.getAppId())
                .apiTotalData(apiTotalData)
                .deductedTotalBytes(0L)
                .apiRemainingData(apiTotalData)
                .finalStatus(TrafficFinalStatus.FAILED.name())
                .lastLuaStatus(TrafficLuaStatus.ERROR.name())
                .startedAt(now)
                .finishedAt(now)
                .latency(normalizeLatency(latency))
                .restoreLastErrorMessage(sanitizeRestoreLastErrorMessage(restoreLastErrorMessage))
                .build();

        // non-retryable 종결 전용 INSERT SQL 구현을 saveWithRetry에 주입합니다.
        return saveWithRetry(doneLog, trafficDeductDoneLogMapper::insertNonRetryableFailure);
    }

    private boolean saveWithRetry(
            TrafficDeductDoneLog doneLog,
            DoneLogInsertOperation insertOperation
    ) {
        DataAccessException lastException = null;
        for (int retryCount = 0; retryCount <= DONE_LOG_DB_RETRY_MAX; retryCount++) {
            try {
                int affectedRows = insertOperation.insert(doneLog);
                if (affectedRows != 1) {
                    throw new IllegalStateException(
                            "traffic_done_log_insert_unexpected_row_count: " + affectedRows
                    );
                }
                return true;
            } catch (DuplicateKeyException e) {
                // 이미 같은 trace_id가 존재하면 정상적인 중복 완료로 간주한다.
                return false;
            } catch (DataAccessException e) {
                lastException = e;
                boolean retryable = isRetryableDbException(e);
                if (!retryable || retryCount >= DONE_LOG_DB_RETRY_MAX) {
                    throw e;
                }
                sleepDoneLogRetryBackoff(retryCount + 1);
            }
        }

        throw lastException == null
                ? new IllegalStateException("traffic_done_log_insert_retry_exhausted")
                : lastException;
    }

    /**
     * 입력값이 없을 때 사용할 기본 시간을 반환합니다.
     */
    private LocalDateTime defaultNowIfNull(LocalDateTime value) {
        if (value != null) {
            return value;
        }
        return LocalDateTime.now();
    }

    /**
     * 비정상 음수 지연값이 DB에 저장되지 않도록 0 이상으로 정규화합니다.
     */
    private long normalizeLatency(Long latency) {
        if (latency == null) {
            return 0L;
        }
        return Math.max(0L, latency);
    }

    /**
     * 음수/NULL 입력이 DB에 저장되지 않도록 0 이상으로 정규화합니다.
     */
    private long normalizeNonNegative(Long value) {
        if (value == null) {
            return 0L;
        }
        return Math.max(0L, value);
    }

    /**
     * 요약 문자열 생성은 호출부에서 수행하고, 저장 시점에는 길이 상한(1000자)만 보정합니다.
     */
    private String sanitizeRestoreLastErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        if (message.length() <= RESTORE_LAST_ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, RESTORE_LAST_ERROR_MESSAGE_MAX_LENGTH);
    }

    /**
     * done log insert 경로에서 재시도 가능한 DB 예외인지 판별합니다.
     */
    private boolean isRetryableDbException(DataAccessException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof QueryTimeoutException
                    || current instanceof CannotAcquireLockException
                    || current instanceof DeadlockLoserDataAccessException
                    || current instanceof PessimisticLockingFailureException
                    || current instanceof ConcurrencyFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * done log insert 재시도 전 지수 backoff(50/100/200ms) 만큼 대기합니다.
     */
    private void sleepDoneLogRetryBackoff(int retryAttempt) {
        long delayMs = TrafficRetryBackoffSupport.resolveDelayMs(DONE_LOG_DB_RETRY_BASE_MS, retryAttempt);
        if (delayMs <= 0L) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("traffic_done_log_retry_sleep_interrupted", interruptedException);
        }
    }

}
