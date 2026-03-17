# Cache Redis AOF 백업 가이드

## 1. 백업 대상 범위

현재 프로젝트에서 아래 traffic cache 키들은 `cache Redis` 인스턴스에 저장됩니다.
AOF는 키 단위가 아니라 Redis 인스턴스 단위로 동작하므로, `cache Redis`에 AOF를 켜면 아래 키들이 함께 백업됩니다.

- `policy:{policyId}`
- `daily_total_limit:{lineId}`
- `monthly_shared_limit:{lineId}`
- `app_data_daily_limit:{lineId}`
- `app_speed_limit:{lineId}`
- `app_whitelist:{lineId}`
- `immediately_block_end:{lineId}`
- `repeat_block:{lineId}`
- `remaining_indiv_amount:{lineId}:{yyyymm}`
- `remaining_shared_amount:{familyId}:{yyyymm}`
- `daily_total_usage:{lineId}:{yyyymmdd}`
- `monthly_shared_usage:{lineId}:{yyyymm}`
- `daily_app_usage:{lineId}:{yyyymmdd}`
- `qos:{lineId}`
- `refill:idempotency:{uuid}`
- `usage_delta:replay:idempotency:{traceId}:{poolType}`

## 2. 왜 cache Redis 단위로 처리했는가

- Redis AOF는 특정 키만 골라서 저장하는 기능이 아니라, 해당 Redis 인스턴스에 들어오는 쓰기 명령 전체를 append-only 파일로 남기는 방식입니다.
- 이 프로젝트는 이미 `session / cache / streams` Redis를 분리해 두었기 때문에, 정책/차감/멱등 키가 들어가는 `cache Redis`에만 AOF를 켜는 것이 가장 단순하고 안전합니다.
- 반대로 같은 `cache Redis`에 들어가는 보조 키들도 함께 AOF에 기록됩니다.
  - 예: hydrate lock, speed bucket 등
- 만약 위 목록만 정말 따로 보존해야 한다면, 그 경우에는 AOF 옵션으로 해결하는 것이 아니라 Redis 인스턴스를 더 쪼개야 합니다.

## 3. 이번에 바뀐 점

### 3-1. 설정 프로퍼티 추가

파일:

- `src/main/resources/application.yaml`
- `src/main/java/com/pooli/common/config/CacheRedisProperties.java`

추가한 프로퍼티:

- `app.redis.cache.aof.enabled`
- `app.redis.cache.aof.configure-on-startup`
- `app.redis.cache.aof.fail-fast`
- `app.redis.cache.aof.appendfsync`
- `app.redis.cache.aof.auto-rewrite-percentage`
- `app.redis.cache.aof.auto-rewrite-min-size`
- `app.redis.cache.aof.use-rdb-preamble`
- `app.redis.cache.aof.rewrite-config-on-startup`
- `app.redis.cache.aof.trigger-background-rewrite-on-startup`

변경 이유:

- AOF를 항상 같은 방식으로 쓰는 것은 아닙니다.
- 어떤 환경은 앱이 Redis 설정을 직접 바꿀 수 있고, 어떤 환경은 관리형 Redis라서 검증만 해야 합니다.
- 그래서 "켜기", "부팅 시 설정 적용", "검증 실패 시 중단", "초기 rewrite 여부"를 각각 분리해서 운영자가 환경에 맞게 선택할 수 있게 했습니다.

### 3-2. 부팅 시 AOF 강제/검증 서비스 추가

파일:

- `src/main/java/com/pooli/common/config/CacheRedisAofBackupService.java`

이 서비스가 하는 일:

1. AOF 기능이 켜져 있는지 확인
2. 필요하면 `CONFIG SET`으로 Redis 설정 적용
3. 필요하면 `CONFIG REWRITE` 수행
4. `CONFIG GET`으로 실제 서버값 재확인
5. 필요하면 `BGREWRITEAOF` 수행

변경 이유:

- 환경 변수에 "AOF를 켠다"고 적어도 실제 Redis 서버가 그 값으로 동작하는지는 별개입니다.
- 특히 운영 중에는 `redis.conf`, ACL, 관리형 Redis 제약 때문에 기대값과 실제값이 달라질 수 있습니다.
- 그래서 "설정 적용"만 하지 않고, 적용 후 실제 서버 상태를 다시 읽어 검증하도록 만들었습니다.
- 이 과정을 거치면 정책/차감 키가 AOF 없이 운영되는 실수를 초기에 막을 수 있습니다.

### 3-3. 부팅 훅 추가

파일:

- `src/main/java/com/pooli/common/config/CacheRedisConfig.java`

변경 이유:

- AOF 검증은 사람이 수동으로 호출하면 놓치기 쉽습니다.
- 따라서 `ApplicationRunner`로 앱 시작 직후 자동 실행되게 붙였습니다.
- 이렇게 하면 traffic 로직이 cache Redis를 쓰기 시작하기 전에 AOF 상태를 먼저 확인할 수 있습니다.

### 3-4. 테스트 추가

파일:

- `src/test/java/com/pooli/common/config/CacheRedisAofBackupServiceTest.java`

검증한 시나리오:

- AOF 비활성 시 아무 작업도 하지 않는지
- AOF 활성 시 설정과 검증을 수행하는지
- 검증 전용 모드에서 기대값이 다르면 실패하는지
- fail-fast가 꺼져 있으면 경고만 남기고 진행하는지

변경 이유:

- AOF 로직은 운영 장애와 직접 연결되므로, 단순 설정 파일 변경보다 검증 코드의 동작을 테스트로 고정하는 편이 안전합니다.
- 특히 fail-fast / non-fail-fast 분기는 운영 정책 차이와 직결되기 때문에 테스트가 필요했습니다.

## 4. 추천 설정 방법

### 4-1. 직접 운영하는 cache Redis

추천 환경 변수:

```env
CACHE_REDIS_AOF_ENABLED=true
CACHE_REDIS_AOF_CONFIGURE_ON_STARTUP=true
CACHE_REDIS_AOF_FAIL_FAST=true
CACHE_REDIS_AOF_APPEND_FSYNC=everysec
CACHE_REDIS_AOF_AUTO_REWRITE_PERCENTAGE=100
CACHE_REDIS_AOF_AUTO_REWRITE_MIN_SIZE=64mb
CACHE_REDIS_AOF_USE_RDB_PREAMBLE=true
CACHE_REDIS_AOF_REWRITE_CONFIG_ON_STARTUP=true
CACHE_REDIS_AOF_TRIGGER_BACKGROUND_REWRITE_ON_STARTUP=false
```

권장 Redis 설정:

```conf
appendonly yes
appendfsync everysec
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
aof-use-rdb-preamble yes
```

이 방식을 추천하는 이유:

- Redis가 애플리케이션보다 먼저 올라와도 이미 AOF가 켜진 상태를 보장할 수 있습니다.
- 앱은 그 설정을 한 번 더 검증하는 안전망 역할만 하면 됩니다.

### 4-2. 관리형 Redis 또는 CONFIG 명령이 막힌 환경

추천 환경 변수:

```env
CACHE_REDIS_AOF_ENABLED=true
CACHE_REDIS_AOF_CONFIGURE_ON_STARTUP=false
CACHE_REDIS_AOF_FAIL_FAST=true
```

이 방식을 쓰는 이유:

- 일부 환경에서는 `CONFIG SET`, `CONFIG REWRITE`가 허용되지 않습니다.
- 이 경우 앱이 Redis 설정을 바꾸려 하지 않고, 인프라에서 미리 켜 둔 AOF 상태만 검증하도록 해야 합니다.

## 5. 운영 시 주의점

- `CONFIG REWRITE`는 Redis가 설정 파일을 실제로 쓸 수 있는 환경이어야 동작합니다.
- `BGREWRITEAOF`는 비용이 있는 작업이라 기본값은 `false`로 두었습니다.
- 앱이 fail-fast 모드에서 기동 실패한다면, 그 의미는 "cache Redis가 아직 AOF 백업 경계로 안전하지 않다"는 뜻입니다.

## 6. 검증 결과

이번 변경 후 확인한 항목:

- `.\gradlew.bat compileJava compileTestJava` 성공

제한사항:

- 현재 저장소의 `.\gradlew.bat test`는 기존부터 Gradle/JUnit 클래스 로딩 문제로 전체 테스트가 `ClassNotFoundException` 상태입니다.
- 따라서 전체 테스트 통과 여부가 아니라, 이번 변경분의 컴파일 성공과 테스트 클래스 생성까지 확인한 상태입니다.
