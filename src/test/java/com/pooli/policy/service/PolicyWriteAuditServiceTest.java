package com.pooli.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pooli.policy.domain.entity.AppPolicy;
import com.pooli.policy.domain.entity.LineLimit;

@ExtendWith(MockitoExtension.class)
class PolicyWriteAuditServiceTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private MongoConverter mongoConverter;

    @InjectMocks
    private PolicyWriteAuditService policyWriteAuditService;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            Object source = invocation.getArgument(0);
            Document target = invocation.getArgument(1);

            if (source instanceof LineLimit lineLimit) {
                target.put("limitId", lineLimit.getLimitId());
                target.put("lineId", lineLimit.getLineId());
                target.put("dailyDataLimit", lineLimit.getDailyDataLimit());
                target.put("isDailyLimitActive", lineLimit.getIsDailyLimitActive());
                target.put("sharedDataLimit", lineLimit.getSharedDataLimit());
                target.put("isSharedLimitActive", lineLimit.getIsSharedLimitActive());
                target.put("createdAt", lineLimit.getCreatedAt());
                target.put("deletedAt", lineLimit.getDeletedAt());
                target.put("updatedAt", lineLimit.getUpdatedAt());
            }

            if (source instanceof AppPolicy appPolicy) {
                target.put("appPolicyId", appPolicy.getAppPolicyId());
                target.put("lineId", appPolicy.getLineId());
                target.put("applicationId", appPolicy.getApplicationId());
                target.put("dataLimit", appPolicy.getDataLimit());
                target.put("speedLimit", appPolicy.getSpeedLimit());
                target.put("isActive", appPolicy.getIsActive());
                target.put("isWhitelist", appPolicy.getIsWhitelist());
                target.put("createdAt", appPolicy.getCreatedAt());
                target.put("deletedAt", appPolicy.getDeletedAt());
                target.put("updatedAt", appPolicy.getUpdatedAt());
            }
            return null;
        }).when(mongoConverter).write(any(), any(Document.class));

        org.mockito.Mockito.when(mongoTemplate.getConverter()).thenReturn(mongoConverter);
    }

    @Test
    @DisplayName("UPDATE 이벤트는 실제 변경 컬럼만 update 필드에 저장한다")
    void saveWriteAuditAfterCommit_lineLimitUpdate_savesChangedFieldsOnly() {
        // given
        LineLimit preImage = LineLimit.builder()
                .limitId(1L)
                .lineId(100L)
                .dailyDataLimit(1000L)
                .isDailyLimitActive(false)
                .sharedDataLimit(3000L)
                .isSharedLimitActive(true)
                .build();
        LineLimit postImage = LineLimit.builder()
                .limitId(1L)
                .lineId(100L)
                .dailyDataLimit(1000L)
                .isDailyLimitActive(true)
                .sharedDataLimit(3000L)
                .isSharedLimitActive(true)
                .build();

        // when
        policyWriteAuditService.saveLineLimitWriteAuditAfterCommit(
                PolicyWriteEventType.UPDATE,
                "toggleDailyTotalLimitPolicy",
                preImage,
                postImage,
                1L,
                100L
        );

        // then
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoTemplate).insert(documentCaptor.capture(), eq("policy_history"));

        Document auditDocument = documentCaptor.getValue();
        assertThat(auditDocument.get("table_name")).isEqualTo("LINE_LIMIT");
        assertThat(auditDocument.get("record_pk")).isEqualTo(1L);
        assertThat(auditDocument.get("actor_user_id")).isEqualTo(1L);
        assertThat(auditDocument.get("actor_line_id")).isEqualTo(100L);
        assertThat(auditDocument.get("event")).isEqualTo("UPDATE");
        assertThat(auditDocument.get("timestamp")).isInstanceOf(LocalDateTime.class);

        Document update = (Document) auditDocument.get("update");
        assertThat(update).containsKey("is_daily_limit_active");
        assertThat(update).doesNotContainKey("daily_data_limit");

        Document changedField = (Document) update.get("is_daily_limit_active");
        assertThat(changedField.get("before")).isEqualTo(false);
        assertThat(changedField.get("after")).isEqualTo(true);
    }

    @Test
    @DisplayName("CREATE 이벤트는 null -> 값으로 변경된 컬럼을 update에 저장한다")
    void saveWriteAuditAfterCommit_appPolicyCreate_includesCreateDiff() {
        // given
        AppPolicy postImage = AppPolicy.builder()
                .appPolicyId(7L)
                .lineId(100L)
                .applicationId(10)
                .dataLimit(5000L)
                .speedLimit(70)
                .isActive(true)
                .isWhitelist(false)
                .build();

        // when
        policyWriteAuditService.saveAppPolicyWriteAuditAfterCommit(
                PolicyWriteEventType.CREATE,
                "toggleAppPolicyActive",
                null,
                postImage,
                1L,
                null
        );

        // then
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoTemplate).insert(documentCaptor.capture(), eq("policy_history"));
        Document auditDocument = documentCaptor.getValue();
        assertThat(auditDocument.get("table_name")).isEqualTo("APP_POLICY");
        assertThat(auditDocument.get("event")).isEqualTo("CREATE");
        assertThat(auditDocument.get("actor_line_id")).isNull();

        Document update = (Document) auditDocument.get("update");
        Document appPolicyIdDiff = (Document) update.get("app_policy_id");
        assertThat(appPolicyIdDiff.get("before")).isNull();
        assertThat(appPolicyIdDiff.get("after")).isEqualTo(7L);
    }

    @Test
    @DisplayName("변경 사항이 없으면 감사 문서를 저장하지 않는다")
    void saveWriteAuditAfterCommit_whenNoDiff_doesNotInsert() {
        // given
        AppPolicy image = AppPolicy.builder()
                .appPolicyId(9L)
                .lineId(100L)
                .applicationId(20)
                .dataLimit(100L)
                .speedLimit(50)
                .isActive(true)
                .isWhitelist(false)
                .build();

        // when
        policyWriteAuditService.saveAppPolicyWriteAuditAfterCommit(
                PolicyWriteEventType.UPDATE,
                "updateAppDataLimit",
                image,
                image,
                1L,
                100L
        );

        // then
        verify(mongoTemplate, never()).insert(any(Document.class), eq("policy_history"));
    }

    @Test
    @DisplayName("Mongo 저장 실패는 예외 전파 없이 흡수한다")
    void saveWriteAuditAfterCommit_whenMongoFails_doesNotThrow() {
        // given
        doThrow(new RuntimeException("mongo down"))
                .when(mongoTemplate).insert(any(Document.class), eq("policy_history"));

        // when & then
        assertDoesNotThrow(() -> policyWriteAuditService.saveLineLimitWriteAuditAfterCommit(
                PolicyWriteEventType.UPDATE,
                "updateDailyTotalLimitPolicyValue",
                LineLimit.builder().limitId(1L).lineId(100L).dailyDataLimit(10L).build(),
                LineLimit.builder().limitId(1L).lineId(100L).dailyDataLimit(20L).build(),
                1L,
                100L
        ));
    }

    @Test
    @DisplayName("트랜잭션이 활성화된 경우 afterCommit 시점에 저장한다")
    void saveWriteAuditAfterCommit_whenTransactionActive_writesAfterCommit() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            policyWriteAuditService.saveLineLimitWriteAuditAfterCommit(
                    PolicyWriteEventType.UPDATE,
                    "toggleSharedPoolLimitPolicy",
                    LineLimit.builder().limitId(10L).lineId(100L).isSharedLimitActive(false).build(),
                    LineLimit.builder().limitId(10L).lineId(100L).isSharedLimitActive(true).build(),
                    1L,
                    100L
            );

            // then
            verify(mongoTemplate, never()).insert(any(Document.class), eq("policy_history"));
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).isNotEmpty();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            verify(mongoTemplate).insert(any(Document.class), eq("policy_history"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
