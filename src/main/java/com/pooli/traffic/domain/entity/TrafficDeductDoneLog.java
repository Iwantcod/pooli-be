package com.pooli.traffic.domain.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트래픽 차감 완료 로그를 MongoDB에 저장하는 문서 엔티티입니다.
 * 운영 조회 편의를 위해 중첩 body 없이 일반 필드로만 구성합니다.
 */
@Document(collection = "traffic_deduct_done_log")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TrafficDeductDoneLog {

    @Id
    private String id;

    @Field("trace_id")
    private String traceId;

    @Field("record_id")
    private String recordId;

    @Field("line_id")
    private Long lineId;

    @Field("family_id")
    private Long familyId;

    @Field("app_id")
    private Integer appId;

    @Field("api_total_data")
    private Long apiTotalData;

    @Field("deducted_total_bytes")
    private Long deductedTotalBytes;

    @Field("api_remaining_data")
    private Long apiRemainingData;

    @Field("final_status")
    private String finalStatus;

    @Field("last_lua_status")
    private String lastLuaStatus;

    @Field("created_at")
    private LocalDateTime createdAt;

    @Field("finished_at")
    private LocalDateTime finishedAt;

    @Field("logged_at")
    private LocalDateTime loggedAt;
}
