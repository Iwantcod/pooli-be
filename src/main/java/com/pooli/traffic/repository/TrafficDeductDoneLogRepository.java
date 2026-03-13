package com.pooli.traffic.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * 트래픽 차감 완료 로그 문서의 조회/삽입을 담당하는 Mongo 저장소입니다.
 */
@Repository
public interface TrafficDeductDoneLogRepository extends MongoRepository<TrafficDeductDoneLog, String> {

    boolean existsByTraceId(String traceId);
}
