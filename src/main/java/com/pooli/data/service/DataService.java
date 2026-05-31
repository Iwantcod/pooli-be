package com.pooli.data.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.DataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;

/**
 * 회선 데이터 사용량과 잔량 조회를 담당하는 서비스 계약입니다.
 *
 * <p>컨트롤러 계층에는 월별 총 사용량, 앱별 사용량, 현재 잔량 요약처럼 화면 응답에 필요한 조회 단위를 제공하고,
 * 구현체는 DB 집계값과 Redis 실시간 사용량/잔량 보정 정책을 결합해 응답 DTO를 구성합니다.</p>
 */
public interface DataService {

	/**
	 * 지정한 회선의 월별 개인풀/공유풀 사용량 추이를 조회합니다.
	 *
	 * @param lineId 조회 대상 회선 id
	 * @param yearMonth 조회 기준 월. `yyyyMM` 형식을 사용합니다.
	 * @return 월별 데이터 사용량 목록 응답
	 */
	MonthlyDataUsageResDto getMonthlyDataUsage(Long lineId, Integer yearMonth);

	/**
	 * 지정한 회선의 앱별 데이터 사용량을 월 단위로 조회합니다.
	 *
	 * @param lineId 조회 대상 회선 id
	 * @param yearMonth 조회 기준 월. `yyyyMM` 형식을 사용합니다.
	 * @param principal 현재 인증 사용자 정보. 회선 접근 권한 판단에 사용합니다.
	 * @return 앱별 데이터 사용량 응답
	 */
	AppDataUsageResDto getAppDataUsage(Long lineId, Integer yearMonth, AuthUserDetails principal );

	/**
	 * 지정한 회선의 현재 데이터 잔량 요약을 조회합니다.
	 *
	 * @param lineId 조회 대상 회선 id
	 * @return 개인풀/공유풀 잔량과 기본 회선 표시 정보를 포함한 요약 응답
	 */
	DataBalancesResDto getDataSummary(Long lineId);

	/**
	 * 지정한 회선의 특정 월 개인풀/공유풀 사용량과 현재월 표시용 총량/잔량을 조회합니다.
	 *
	 * @param lineId 조회 대상 회선 id
	 * @param yearMonth 조회 기준 월. `yyyyMM` 형식을 사용합니다.
	 * @return 월 사용량 상세 응답
	 */
	DataUsageResDto getDataUsage(Long lineId, Integer yearMonth);
}
