package com.pooli.policy.domain.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "policy_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PolicyHistory {

    @Id
    private String id;

    private String tableName;

    private LocalDateTime timestamp;

    private String targetId;

    private String userId;

    private Long lineId;

    private String event;

    private Map<String, Object> update;

    @Getter
    @Builder
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class UpdateDetail {
        private Object before;
        private Object after;
    }
}
