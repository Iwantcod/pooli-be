# Redis-Only Release Checklist

## 상태 정의
- `SUCCESS`: 요청 데이터가 전량 처리되었고 `individual + shared + qos == api_total`이다.
- `PARTIAL_SUCCESS`: 개인풀, 공유풀, QoS 중 일부가 처리되었지만 `remaining > 0`이다.
- `NOT_DEDUCTED`: 정책 차단, 정책 cap, 또는 잔량/QoS 부족으로 최종 처리량이 `0`이다.
- `FAILED`: 처리 중 복구 불가능한 실패로 종료된 결과이다.
- Invalid payload/month-boundary 메시지는 done log 없이 DLQ + ACK로 종결한다.

## Done Log 해석
- 총 처리량은 저장 컬럼이 아니라 `deducted_individual_bytes + deducted_shared_bytes + deducted_qos_bytes`의 파생값이다.
- `deducted_individual_bytes`는 개인풀 Redis 잔량에서 실제 차감된 양이다.
- `deducted_shared_bytes`는 공유풀 Redis 잔량에서 실제 차감된 양이다.
- `deducted_qos_bytes`는 QoS 정책으로 처리된 양이며 개인/공유 잔량과 월간 공유풀 사용량에는 반영하지 않는다.
- `enqueued_at` 기준 월/일 키를 사용한다.
- 앱 속도 제한 및 QoS가 적용된 요청의 `finished_at`은 Java 처리 시각이 아니라 Redis Lua가 반환한 `finishedAtEpochMillis`를 서비스 timezone으로 변환한 값이다.

## Redis 서버 버전 확인
- 현재 repository의 `docker-compose.yml`은 Redis 서버 이미지를 고정하지 않고, 배포 환경 Redis는 `.env`의 host/port로 주입된다.
- M5 확인 결과: `.env` 기준 cache Redis와 streams Redis 모두 `INFO server`의 `redis_version`이 `7.4.2`.
- 배포 전 cache Redis와 streams Redis 각각에 대해 `INFO server`의 `redis_version`을 확인하고 릴리즈 기록에 남긴다.
- 차감 Lua는 Redis `TIME`을 사용하므로, 운영 Redis 서버는 Lua 실행 환경에서 `TIME` 명령을 지원해야 한다.

## 장애 지표
- `traffic_redis_ops_total{redis="cache|streams"}`: Redis 종류별 호출 수.
- `traffic_redis_failures_total{redis="cache|streams",kind="timeout|connection|non_retryable"}`: Redis 종류/실패 유형별 실패 수.
- `traffic_redis_ping_up{redis="cache|streams"}`: PING 성공 시 `1`, 실패 시 `0`.
- `traffic_redis_ping_failures{redis="cache|streams"}`: Redis 종류별 연속 PING 실패 횟수.
- Prometheus 경보는 timeout + connection 실패율만 사용하고, non-retryable 실패는 가용성 경보율에서 제외한다.

## 릴리즈 전 검증
- `./gradlew test` 통과.
- 핵심 회귀 범위 확인: 정상 차감, 정책 차단, `NO_BALANCE`, 앱 속도 제한, QoS, 앱 속도 제한+QoS 동시 적용, 속도 제한 timeout, 정책 비활성화, invalid, reclaim.
- cache Redis와 streams Redis의 `redis_version` 확인 결과 기록.
- Prometheus rule 조건 확인: warning 5분 10%, critical 1분 30%, global-down 1분 70% + PING 연속 실패 5회.
- Alertmanager Discord webhook은 secret 파일 주입 방식으로 구성하고 repository에 webhook URL을 남기지 않는다.
