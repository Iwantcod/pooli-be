package com.pooli.family.domain.dto.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Document(collection = "shared_pool_transfer_logs")
public class SharedPoolTransferLog {

    @Id
    private String id;

    private Long familyId;

    private Long lineId;

    private Long amount;

    private Instant createdAt;
}