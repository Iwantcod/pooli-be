package com.pooli.policy.domain.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "admin_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AdminHistory {

    @Id
    private String id;

    private String tableName;

    private LocalDateTime timestamp;

    private String targetId;

    private String userId;

    private String event;

    private Map<String, Object> update;

}
