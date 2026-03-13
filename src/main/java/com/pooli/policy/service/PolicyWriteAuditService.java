package com.pooli.policy.service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

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
 * <p>- before/after 엔티티를 비교해 변경 컬럼만 update 필드로 저장
 * <p>- 저장 시점은 트랜잭션 커밋 이후(afterCommit)로 맞춰 정합성 유지
 * <p>- Mongo 저장 실패는 비즈니스 흐름을 깨지 않도록 로그만 남기고 흡수(best-effort)
 */
public class PolicyWriteAuditService {

    private static final String POLICY_HISTORY_COLLECTION = "policy_history";
    private static final String TABLE_LINE_LIMIT = "LINE_LIMIT";
    private static final String TABLE_APP_POLICY = "APP_POLICY";

    private final MongoTemplate mongoTemplate;

    /**
     * LINE_LIMIT 쓰기 이벤트 감사 문서를 저장합니다.
     */
    public void saveLineLimitWriteAuditAfterCommit(
            PolicyWriteEventType eventType,
            String sourceMethod,
            LineLimit preImage,
            LineLimit postImage,
            Long actorUserId,
            Long actorLineId
    ) {
        if (eventType == null || (preImage == null && postImage == null)) {
            return;
        }

        Object recordPk = resolveRecordPk(
                preImage != null ? preImage.getLimitId() : null,
                postImage != null ? postImage.getLimitId() : null
        );

        saveAuditAfterCommit(
                TABLE_LINE_LIMIT,
                eventType,
                sourceMethod,
                recordPk,
                preImage,
                postImage,
                actorUserId,
                actorLineId
        );
    }

    /**
     * APP_POLICY 쓰기 이벤트 감사 문서를 저장합니다.
     */
    public void saveAppPolicyWriteAuditAfterCommit(
            PolicyWriteEventType eventType,
            String sourceMethod,
            AppPolicy preImage,
            AppPolicy postImage,
            Long actorUserId,
            Long actorLineId
    ) {
        if (eventType == null || (preImage == null && postImage == null)) {
            return;
        }

        Object recordPk = resolveRecordPk(
                preImage != null ? preImage.getAppPolicyId() : null,
                postImage != null ? postImage.getAppPolicyId() : null
        );

        saveAuditAfterCommit(
                TABLE_APP_POLICY,
                eventType,
                sourceMethod,
                recordPk,
                preImage,
                postImage,
                actorUserId,
                actorLineId
        );
    }

    /**
     * before/after를 비교해 공통 감사 문서를 만들고 afterCommit 시점에 저장합니다.
     */
    private void saveAuditAfterCommit(
            String tableName,
            PolicyWriteEventType eventType,
            String sourceMethod,
            Object recordPk,
            Object preImage,
            Object postImage,
            Long actorUserId,
            Long actorLineId
    ) {
        Document before = buildSnakeCaseDocument(preImage);
        Document after = buildSnakeCaseDocument(postImage);
        Document update = buildUpdateDocument(before, after);

        // 실제로 값이 달라진 컬럼이 없으면 감사 문서를 남기지 않습니다.
        if (update.isEmpty()) {
            return;
        }

        Document auditDocument = new Document();
        auditDocument.put("table_name", tableName);
        auditDocument.put("timestamp", LocalDateTime.now());
        auditDocument.put("record_pk", recordPk);
        auditDocument.put("actor_user_id", actorUserId);
        auditDocument.put("actor_line_id", actorLineId);
        auditDocument.put("event", eventType.name());
        auditDocument.put("update", update);

        executeAfterCommit(auditDocument, eventType, sourceMethod, recordPk, actorLineId);
    }

    /**
     * Mongo 저장 실행 타이밍을 조정합니다.
     *
     * <p>- 트랜잭션 활성: afterCommit 콜백에 등록
     * <p>- 트랜잭션 비활성: 즉시 실행
     */
    private void executeAfterCommit(
            Document auditDocument,
            PolicyWriteEventType eventType,
            String sourceMethod,
            Object recordPk,
            Long lineId
    ) {
        Runnable writeOperation = () -> {
            try {
                mongoTemplate.insert(auditDocument, POLICY_HISTORY_COLLECTION);
            } catch (RuntimeException e) {
                log.warn(
                        "policy_history_write_failed event_type={} method={} pk={} line_id={}",
                        eventType,
                        sourceMethod,
                        recordPk,
                        lineId,
                        e
                );
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeOperation.run();
                }
            });
            return;
        }

        writeOperation.run();
    }

    /**
     * Entity/DTO를 snake_case 필드명 문서로 정규화합니다.
     */
    private Document buildSnakeCaseDocument(Object source) {
        if (source == null) {
            return new Document();
        }

        Document mapped = new Document();
        mongoTemplate.getConverter().write(source, mapped);

        Document normalized = new Document();
        mapped.forEach((key, value) -> {
            if ("_class".equals(key)) {
                return;
            }
            normalized.put(toSnakeCase(key), value);
        });
        return normalized;
    }

    /**
     * before/after 값이 다른 컬럼만 update 문서로 구성합니다.
     */
    private Document buildUpdateDocument(Document before, Document after) {
        Document update = new Document();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        for (String key : keys) {
            Object beforeValue = before.get(key);
            Object afterValue = after.get(key);
            if (Objects.equals(beforeValue, afterValue)) {
                continue;
            }
            update.put(key, new Document("before", beforeValue).append("after", afterValue));
        }

        return update;
    }

    private Object resolveRecordPk(Object preRecordPk, Object postRecordPk) {
        return preRecordPk != null ? preRecordPk : postRecordPk;
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
