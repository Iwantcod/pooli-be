package com.pooli.traffic.domain.outbox;

/**
 * Outbox 생성 결과입니다. duplicate은 이미 접수된 trace/event 조합을 의미합니다.
 */
public record OutboxCreateResult(boolean created, Long outboxId) {

    public static OutboxCreateResult created(long outboxId) {
        return new OutboxCreateResult(true, outboxId);
    }

    public static OutboxCreateResult duplicate() {
        return new OutboxCreateResult(false, null);
    }
}
