package com.pooli.traffic.domain.outbox;

/**
 * Outbox 생성 결과입니다. duplicate은 이미 접수된 trace/event 조합을 의미합니다.
 */
public record OutboxCreateResult(boolean created, Long outboxId) {

    /** Outbox 레코드가 성공적으로 신규 생성되었음을 나타내는 팩토리 메서드 */
    public static OutboxCreateResult created(long outboxId) {
        return new OutboxCreateResult(true, outboxId);
    }

    /** Outbox 레코드가 이미 존재하여 중복 처리되었음을 나타내는 팩토리 메서드 */
    public static OutboxCreateResult duplicate() {
        return new OutboxCreateResult(false, null);
    }
}
