package com.pooli.policy.mapper;

import java.time.LocalDateTime;
import java.time.LocalTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.policy.domain.enums.DayOfWeek;

@Mapper
public interface BlockStatusMapper {

    /**
     * 현재 시각 기준으로 활성화된 모든 차단(즉시 + 반복) 중
     * 가장 늦게 끝나는 종료 시각을 단일 쿼리로 조회합니다.
     *
     * - 즉시 차단: LINE.block_end_at > #{now} 인 경우
     * - 반복 차단: 오늘 요일(#{dayOfWeek})이 일치하고, 현재 시각(#{nowTime})이 [start_at, end_at] 구간에 포함되는 경우
     *
     * @param lineId    회선 ID
     * @param dayOfWeek 현재 요일 (SUN, MON, ... SAT)
     * @param nowTime   현재 시각 (LocalTime, 반복 차단 구간 비교용)
     * @param now       현재 일시 (LocalDateTime, 즉시 차단 비교용)
     * @return 가장 늦게 끝나는 차단 종료 일시. 활성화된 차단이 없으면 null.
     */
    LocalDateTime selectLatestBlockEndAt(
            @Param("lineId")    Long lineId,
            @Param("dayOfWeek") DayOfWeek dayOfWeek,
            @Param("nowTime")   LocalTime nowTime,
            @Param("now")       LocalDateTime now
    );
}
