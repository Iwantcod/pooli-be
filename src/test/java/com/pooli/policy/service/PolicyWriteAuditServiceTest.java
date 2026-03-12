package com.pooli.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

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
    @DisplayName("LINE_LIMIT pre-image는 snake_case 문서로 line_limit_write_audit 컬렉션에 저장한다")
    void savePreImageAfterCommit_lineLimit_savesWithSnakeCaseAndMeta() {
        // given
        LineLimit preImage = LineLimit.builder()
                .limitId(1L)
                .lineId(100L)
                .dailyDataLimit(1000L)
                .isDailyLimitActive(true)
                .sharedDataLimit(3000L)
                .isSharedLimitActive(false)
                .build();

        // when
        policyWriteAuditService.saveLineLimitPreImageAfterCommit(
                PolicyWriteEventType.CHANGE,
                "toggleDailyTotalLimitPolicy",
                preImage,
                1L,
                100L
        );

        // then
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoTemplate).insert(documentCaptor.capture(), eq("line_limit_write_audit"));

        Document document = documentCaptor.getValue();
        assertThat(document.get("limit_id")).isEqualTo(1L);
        assertThat(document.get("line_id")).isEqualTo(100L);
        assertThat(document.get("daily_data_limit")).isEqualTo(1000L);
        assertThat(document.get("is_daily_limit_active")).isEqualTo(true);
        assertThat(document.get("shared_data_limit")).isEqualTo(3000L);
        assertThat(document.get("is_shared_limit_active")).isEqualTo(false);
        assertThat(document.get("audit_event_type")).isEqualTo("CHANGE");
        assertThat(document.get("audit_source_method")).isEqualTo("toggleDailyTotalLimitPolicy");
        assertThat(document.get("audit_actor_user_id")).isEqualTo(1L);
        assertThat(document.get("audit_actor_line_id")).isEqualTo(100L);
        assertThat(document.get("audit_occurred_at")).isInstanceOf(LocalDateTime.class);
    }

    @Test
    @DisplayName("APP_POLICY pre-image는 app_policy_write_audit 컬렉션에 저장한다")
    void savePreImageAfterCommit_appPolicy_usesAppPolicyCollection() {
        // given
        AppPolicy preImage = AppPolicy.builder()
                .appPolicyId(7L)
                .lineId(100L)
                .applicationId(10)
                .dataLimit(5000L)
                .speedLimit(70)
                .isActive(true)
                .isWhitelist(false)
                .build();

        // when
        policyWriteAuditService.saveAppPolicyPreImageAfterCommit(
                PolicyWriteEventType.DELETE,
                "deleteAppPolicy",
                preImage,
                1L,
                100L
        );

        // then
        verify(mongoTemplate).insert(any(Document.class), eq("app_policy_write_audit"));
    }

    @Test
    @DisplayName("Mongo 저장 실패는 예외 전파 없이 흡수한다")
    void savePreImageAfterCommit_whenMongoFails_doesNotThrow() {
        // given
        doThrow(new RuntimeException("mongo down"))
                .when(mongoTemplate).insert(any(Document.class), eq("line_limit_write_audit"));

        // when & then
        assertDoesNotThrow(() -> policyWriteAuditService.saveLineLimitPreImageAfterCommit(
                PolicyWriteEventType.CHANGE,
                "updateDailyTotalLimitPolicyValue",
                LineLimit.builder().limitId(1L).lineId(100L).build(),
                1L,
                100L
        ));
    }

    @Test
    @DisplayName("트랜잭션이 활성화된 경우 afterCommit 시점에 저장한다")
    void savePreImageAfterCommit_whenTransactionActive_writesAfterCommit() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        try {
            // when
            policyWriteAuditService.saveLineLimitPreImageAfterCommit(
                    PolicyWriteEventType.CHANGE,
                    "toggleSharedPoolLimitPolicy",
                    LineLimit.builder().limitId(10L).lineId(100L).build(),
                    1L,
                    100L
            );

            // then
            verify(mongoTemplate, never()).insert(any(Document.class), eq("line_limit_write_audit"));
            List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).isNotEmpty();
            synchronizations.forEach(TransactionSynchronization::afterCommit);
            verify(mongoTemplate).insert(any(Document.class), eq("line_limit_write_audit"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
