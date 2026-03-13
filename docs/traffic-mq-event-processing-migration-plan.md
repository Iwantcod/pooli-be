# MQ 이벤트 단위 전환 상세 실행 계획

## 1. 문서 목적
- 기존 `10초/10틱` 기반 차감 오케스트레이션을 `요청 이벤트 1건 즉시 처리` 방식으로 전환하기 위한 구현 계획을 정의합니다.
- 전환 시에도 아래 조건을 유지합니다.
1. MQ payload 스키마는 변경하지 않음.
2. `traceId` 기반 중복 소비 방지(idempotency) 유지.
3. 정책 검증(Lua) 및 리필(HYDRATE/REFILL) 로직의 핵심 규칙 유지.

## 2. 요구사항 정리
1. 메시지 단위
- 기존: `앞으로 10초 총 데이터량`을 10틱으로 분할 처리.
- 변경: `HTTP 요청 1건 = 데이터 처리 요청 이벤트 1건`.

2. 처리 방식
- 기존: 메시지 1건당 최대 10초 동안 워커 스레드 점유.
- 변경: 메시지 수신 즉시 1회 처리(필요 시 HYDRATE/REFILL 재시도는 유지하되, 1초 tick 대기 제거).

3. 고정 조건
- payload 필드(`traceId`, `lineId`, `familyId`, `appId`, `apiTotalData`, `enqueuedAt`) 유지.
- `traceId`를 중복 소비 대비 키로 지속 사용.

## 3. As-Is 요약 (현재 코드 기준)
1. 생산(API)
- `POST /api/traffic/requests` 호출 시 Streams enqueue.
- `traceId`와 `enqueuedAt` 생성 후 payload JSON으로 적재.

2. 소비(traffic 서버)
- Consumer Group에서 메시지 수신 후 worker 스레드에서 처리.
- 처리 순서: payload 파싱 -> done 로그 체크 -> in-flight dedupe claim -> 오케스트레이션 -> done 저장 -> ACK -> dedupe release.

3. 오케스트레이션
- `MAX_TICKS=10` 루프.
- 매 tick 시작 전 `awaitTickStart(...)`로 1초 슬롯 대기.
- tick마다 개인풀 차감, 필요 시 공유풀 보완 차감.
- HYDRATE/REFILL 경로는 tick 내부에서 동작.

4. 정책 검증/리필
- 정책 검증은 Lua(`deduct_indiv_tick.lua`, `deduct_shared_tick.lua`)에서 원자적으로 수행.
- 리필은 `refill_gate.lua -> lock_heartbeat.lua -> DB 원천 차감 -> Redis refill -> 재차감` 순서.

## 4. To-Be 목표 아키텍처
1. 메시지 처리 모델
- 메시지 1건당 단일 처리 사이클로 종료.
- tick pacing(1초 대기) 제거.

2. 차감 모델
- `apiTotalData`를 이벤트 단위 요청량으로 해석.
- 개인풀 우선 차감 후 부족분이 있고 상태가 `NO_BALANCE`인 경우 공유풀 보완 차감.

3. 상태/정합성 모델
- `traceId` dedupe 및 done 로그 기반 idempotency 유지.
- ACK 정책(`done 저장 성공 후 ACK`) 유지.
- reclaim/DLQ 정책 유지.

## 5. 설계 원칙
1. 의미 변경 최소화
- payload 필드 추가/삭제 없이 처리 semantics만 전환.

2. 정합성 우선
- 정책 검증/리필 루아 계약은 유지하고 상위 오케스트레이션 루프만 단순화.

3. 롤백 가능성 확보
- 가능한 경우 처리 모드 플래그 도입으로 단계적 전환.

4. 테스트 선행
- 기존 10틱 기반 테스트를 이벤트 기반 시나리오로 재정의.

## 6. 구현 단계 (상세)

### Phase 1. 계약/문서 정렬
1. DTO/주석/문서의 의미 정렬
- `TrafficGenerateReqDto.apiTotalData` 설명을 “향후 10초 총량”에서 “요청 이벤트 데이터량(Byte)”으로 수정.
- 운영 문서(`docs/*traffic*`)의 10틱 전제 문구를 이벤트 전제로 교체.

2. 처리 모드 플래그 설계(권장)
- 신규 설정 예시: `app.traffic.processing-mode=EVENT_SINGLE | LEGACY_TICK_10`.
- 1차 배포 시 `LEGACY_TICK_10`, 검증 완료 후 `EVENT_SINGLE` 전환.

완료 기준
- API 계약 문서와 코드 주석에 10초 총량 표현이 남지 않음.

### Phase 2. 오케스트레이터 리팩터링 (핵심)
대상
- `TrafficDeductOrchestratorService`
- (선택) `TrafficTickPacer`, `TrafficSystemTickPacer`, `TrafficTickMetrics`

변경 계획
1. 10틱 루프 제거
- `MAX_TICKS`, `remainingTicks`, `calculateCurrentTickTarget`, `awaitTickStart` 의존 제거.

2. 단일 사이클 로직으로 재구성
- 입력: `requestedData = max(apiTotalData, 0)`.
- Step A: 개인풀 차감(`executeIndividualWithRecovery(payload, requestedData)`).
- Step B: 개인풀 차감량 반영 후 residual 계산.
- Step C: residual > 0 이고 개인풀 상태가 `NO_BALANCE`면 공유풀 차감.
- Step D: 최종 상태 계산(`SUCCESS/PARTIAL_SUCCESS/FAILED`) 및 결과 DTO 생성.

3. 종료 조건 재정의
- `ERROR` 상태 또는 예외: `FAILED`.
- 잔여량 0: `SUCCESS`.
- 잔여량 > 0: `PARTIAL_SUCCESS`.

4. 사용량 버킷 기록 유지
- 개인/공유 실제 차감량 기록은 현재와 동일하게 유지.

완료 기준
- 메시지 1건 처리에 tick 대기 코드가 실행되지 않음.

### Phase 3. Consumer/메트릭 정리
대상
- `TrafficStreamConsumerRunner`
- 모니터링 메트릭 클래스

변경 계획
1. 명칭/로그 정리
- “10-tick 오케스트레이터” 관련 주석/로그 문구 제거.

2. 메트릭 재정의
- `traffic_tick_lag`는 폐기 또는 비활성화.
- 신규 권장:
1. `traffic_event_process_latency`
2. `traffic_event_result_total{result=success|partial|failed}`
3. `traffic_refill_total`, `traffic_hydrate_total`는 유지

3. 처리량 확인 지표 강화
- worker queue 지연, pending 증가율, DLQ 비율 대시보드 업데이트.

완료 기준
- 운영 지표가 이벤트 단위 처리 특성을 반영함.

### Phase 4. 정책/리필 로직 정합성 보강
대상
- `TrafficHydrateRefillAdapterService`
- Lua scripts (`deduct_*`, `refill_gate`, `lock_*`)

변경 계획
1. 파라미터 의미 정렬
- `currentTickTargetData` 명칭을 이벤트 문맥에 맞게 변경(예: `requestedDataBytes`).
- 로직은 동일 계약 유지.

2. 정책 검증 순서 유지 확인
- 개인풀/공유풀 정책 순서 불변.
- `policy:{id}` on/off 기반 분기 불변.

3. 리필 계약 유지 확인
- gate/lock/DB원천차감/Redis충전/재시도 순서 불변.
- `actualRefillAmount`만 Redis에 반영하는 규칙 불변.

완료 기준
- 이벤트 단위 전환 후에도 정책/리필 결과가 기존 규칙과 동일하게 동작.

### Phase 5. 테스트 개편 및 회귀 검증
1. 단위 테스트
- `TrafficDeductOrchestratorServiceTest`를 이벤트 단일 사이클 기준으로 전면 수정.
- 검증 케이스
1. 전량 개인풀 성공
2. 개인풀 부분 성공 + 공유풀 보완 성공
3. 정책 차단(`BLOCKED_*`) 시 부분 성공 종료
4. `ERROR` 시 실패
5. `apiTotalData <= 0` 경계값

2. 통합 테스트
- `TrafficStreamConsumerRunnerTest`
1. 중복 메시지(`traceId`)에서 재차감 방지
2. done 저장 실패 시 ACK 금지
3. reclaim 후 재처리 정상 동작

3. 리필/HYDRATE 테스트
- `TrafficHydrateRefillAdapterServiceTest`
1. HYDRATE 1회 복구
2. gate 상태별(`OK/SKIP/WAIT/FAIL`) 분기
3. DB noop(actual=0) 처리

4. 성능/부하 검증
- 동일 worker 수에서 기존 대비 처리량 개선 확인.
- 목표: pending backlog 및 평균 처리시간 유의미 개선.

완료 기준
- 핵심 트래픽 테스트/회귀 테스트 통과.

## 7. 파일 단위 변경 초안
1. 반드시 수정
- `src/main/java/com/pooli/traffic/service/TrafficDeductOrchestratorService.java`
- `src/main/java/com/pooli/traffic/domain/dto/request/TrafficGenerateReqDto.java`
- `src/test/java/com/pooli/traffic/service/TrafficDeductOrchestratorServiceTest.java`

2. 조건부 수정
- `src/main/java/com/pooli/traffic/service/TrafficSystemTickPacer.java`
- `src/main/java/com/pooli/traffic/service/TrafficTickPacer.java`
- `src/main/java/com/pooli/monitoring/metrics/TrafficTickMetrics.java`
- `src/main/java/com/pooli/monitoring/metrics/*` (신규 이벤트 지표 추가 시)

3. 문서 수정
- `docs/feat-traffic-generator-guide.md`
- `docs/feat-traffic-generator-implementation-plan.md`

## 8. 배포 전략
1. 1차 배포
- 코드 배포 + 모드 플래그 유지(구모드).
- 지표/로그 정상 수집 확인.

2. 2차 전환
- 이벤트 모드 활성화(canary -> 전체).
- DLQ율, pending, 실패율, 처리시간 모니터링.

3. 3차 정리
- 안정화 후 legacy tick 코드 제거.

## 9. 롤백 전략
1. 플래그 기반 롤백
- 즉시 `LEGACY_TICK_10`으로 복귀.

2. 코드 롤백
- 플래그 미도입 시 이전 배포 아티팩트로 롤백.

3. 데이터 정합성
- `traceId` dedupe와 done 로그는 기존과 동일하므로 롤백 시 중복 차감 위험 낮음.

## 10. 리스크 및 대응
1. 리스크: 이벤트 해석 전환 중 `apiTotalData` 의미 혼선
- 대응: DTO 주석/API 문서/운영 가이드 동시 수정.

2. 리스크: 기존 테스트가 tick 전제에 강결합
- 대응: 오케스트레이터 테스트를 이벤트 기준으로 재작성.

3. 리스크: 메트릭 공백
- 대응: 신규 이벤트 지표 선배포 후 모드 전환.

4. 리스크: 숨은 대기/슬립 코드 잔존
- 대응: `tick`, `await`, `sleep` 키워드 기반 코드 스캔 및 리뷰 체크리스트 운영.

## 11. 최종 수용 기준 (Acceptance Criteria)
1. 메시지 1건 처리 시 1초 tick 대기 코드가 호출되지 않는다.
2. `traceId` 기준 중복 소비에서 재차감이 발생하지 않는다.
3. 정책 차단/한도/리필/HYDRATE 동작이 기존 규칙과 일치한다.
4. 기존 대비 worker 점유시간과 pending backlog가 개선된다.
5. 회귀 테스트가 모두 통과한다.
