package com.pooli.traffic.mapper;

import java.time.LocalDate;

/**
 * FAMILY_SHARED_USAGE_DAILY bulk insert 한 row에 필요한 값입니다.
 *
 * @param usageDate   일별 공유풀 사용량 집계 기준 날짜입니다.
 * @param familyId    공유풀 사용량이 귀속되는 가족의 고유 식별자입니다.
 * @param lineId      공유풀을 사용한 회선의 고유 식별자입니다.
 * @param usageAmount 해당 회선의 일일 공유풀 사용 데이터(단위: byte)입니다.
 */
public record DailySharedUsageBatchInsertRow(
        LocalDate usageDate,
        Long familyId,
        Long lineId,
        Long usageAmount
) {
}
