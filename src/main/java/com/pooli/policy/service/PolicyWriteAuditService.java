package com.pooli.policy.service;

import java.time.LocalDateTime;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.entity.LineLimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 정책 쓰기 감사(Audit) 전용 서비스입니다.
 *
 * <p>역할:
 * <p>- RDB 쓰기 직전(pre-image) 엔티티를 Mongo 문서로 변환해 저장
 * <p>- 저장 시점은 트랜잭션 커밋 이후(afterCommit)로 맞춰 정합성 유지
 * <p>- Mongo 저장 실패는 비즈니스 흐름을 깨지 않도록 로그만 남기고 흡수(best-effort)
 */
public class PolicyWriteAuditService {

    private static final String LINE_LIMIT_AUDIT_COLLECTION = "line_limit_write_audit";
    private static final String APP_POLICY_AUDIT_COLLECTION = "app_policy_write_audit";

    private final MongoTemplate mongoTemplate;

    /**
     * LINE_LIMIT 변경/삭제 직전 엔티티를 감사 컬렉션에 저장합니다.
     *
     * <p>문서는 LINE_LIMIT 컬럼명과 동일한 snake_case 키를 사용합니다.
     * <p>실제 Mongo 저장은 {@link #executeAfterCommit(String, Document, PolicyWriteEventType, String, Object, Long)}
     * 를 통해 커밋 이후 시점에 수행됩니다.
     */
    public void saveLineLimitPreImageAfterCommit(
            PolicyWriteEventType eventType,
            String sourceMethod,
            LineLimit preImage,
            Long actorUserId,
            Long actorLineId
    ) {
        // 감사 대상이 없거나 이벤트 타입이 없으면 저장하지 않습니다.
        if (preImage == null || eventType == null) {
            return;
        }

        // 조회한 pre-image(Entity)를 Mongo 문서로 변환한 뒤 snake_case 키로 정규화합니다.
        Document document = buildSnakeCaseDocument(preImage);

        // 공통 감사 메타 정보를 추가합니다.
        appendAuditMetadata(document, eventType, sourceMethod, actorUserId, actorLineId);

        // 커밋 이후에 Mongo 반영되도록 위임합니다.
        executeAfterCommit(
                LINE_LIMIT_AUDIT_COLLECTION,
                document,
                eventType,
                sourceMethod,
                preImage.getLimitId(),
                preImage.getLineId()
        );
    }

    /**
     * APP_POLICY 변경/삭제 직전 엔티티를 감사 컬렉션에 저장합니다.
     *
     * <p>문서는 APP_POLICY 컬럼명과 동일한 snake_case 키를 사용합니다.
     */
    public void saveAppPolicyPreImageAfterCommit(
            PolicyWriteEventType eventType,
            String sourceMethod,
            AppPolicy preImage,
            Long actorUserId,
            Long actorLineId
    ) {
        // 감사 대상이 없거나 이벤트 타입이 없으면 저장하지 않습니다.
        if (preImage == null || eventType == null) {
            return;
        }

        // 조회한 pre-image(Entity)를 Mongo 문서로 변환한 뒤 snake_case 키로 정규화합니다.
        Document document = buildSnakeCaseDocument(preImage);

        // 공통 감사 메타 정보를 추가합니다.
        appendAuditMetadata(document, eventType, sourceMethod, actorUserId, actorLineId);

        // 커밋 이후에 Mongo 반영되도록 위임합니다.
        executeAfterCommit(
                APP_POLICY_AUDIT_COLLECTION,
                document,
                eventType,
                sourceMethod,
                preImage.getAppPolicyId(),
                preImage.getLineId()
        );
    }

    /**
     * Mongo 저장 실행 타이밍을 조정합니다.
     *
     * <p>- 트랜잭션 활성: afterCommit 콜백에 등록
     * <p>- 트랜잭션 비활성: 즉시 실행
     *
     * <p>Mongo 오류는 로그로 남기고 흡수해 본 쓰기 흐름을 유지합니다.
     */
    private void executeAfterCommit(
            String collectionName,
            Document document,
            PolicyWriteEventType eventType,
            String sourceMethod,
            Object primaryKey,
            Long lineId
    ) {
        // Mongo 저장 실패는 best-effort 정책으로 예외 전파하지 않습니다.
        Runnable writeOperation = () -> {
            try {
                mongoTemplate.insert(document, collectionName);
            } catch (RuntimeException e) {
                log.warn(
                        "policy_write_audit_failed event_type={} method={} pk={} line_id={}",
                        eventType,
                        sourceMethod,
                        primaryKey,
                        lineId,
                        e
                );
            }
        };

        // DB 트랜잭션 중이라면 커밋 성공 이후에만 감사 문서를 저장합니다.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeOperation.run();
                }
            });
            return;
        }

        // 트랜잭션이 없다면 즉시 저장합니다.
        writeOperation.run();
    }

    /**
     * 모든 감사 문서에 공통 메타 필드를 채웁니다.
     *
     * <p>누가(actor), 어떤 메서드에서(source), 어떤 이벤트(event), 언제(occurred_at)
     * 발생했는지 추적하기 위한 필드입니다.
     */
    private void appendAuditMetadata(
            Document document,
            PolicyWriteEventType eventType,
            String sourceMethod,
            Long actorUserId,
            Long actorLineId
    ) {
        document.put("audit_event_type", eventType.name());
        document.put("audit_source_method", sourceMethod);
        document.put("audit_occurred_at", LocalDateTime.now());
        document.put("audit_actor_user_id", actorUserId);
        document.put("audit_actor_line_id", actorLineId);
    }

    /**
     * Entity/DTO 기반 pre-image를 Mongo 문서로 변환한 뒤, 필드명을 snake_case로 정규화합니다.
     *
     * <p>요구사항인 "RDB 컬럼명 그대로 저장"을 맞추기 위해 camelCase 프로퍼티명을 snake_case로 변환합니다.
     * <p>Spring Data MongoDB converter가 생성한 내부 타입 메타 필드(_class)는 감사 문서에서 제거합니다.
     */
    private Document buildSnakeCaseDocument(Object preImage) {
        Document mapped = new Document();
        mongoTemplate.getConverter().write(preImage, mapped);

        Document normalized = new Document();
        mapped.forEach((key, value) -> {
            if ("_class".equals(key)) {
                return;
            }
            normalized.put(toSnakeCase(key), value);
        });
        return normalized;
    }

    private String toSnakeCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(ch));
                continue;
            }
            result.append(ch);
        }
        return result.toString();
    }
}
