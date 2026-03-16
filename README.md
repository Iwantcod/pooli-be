# 🐙 POOLI Backend
<p align="center">
  <img src="https://github.com/user-attachments/assets/1672b1f7-73d5-45d0-a228-03eed92a6a01" width="350"/>
</p>

Pooli Backend는 **데이터 차감 트래픽 생성 및 처리 시스템과 이를 모니터링하기 위한 백엔드 서버**입니다.

Spring Boot 기반 API 서버로, Redis Stream 기반 메시지 처리와 Prometheus/Grafana 기반 모니터링을 제공합니다.

---

## Convention

### Code Convention
- https://www.notion.so/yerin1412/Code-Convention-5c8389b3e03983eab2f601a867af08dd

### Git Convention
- https://www.notion.so/yerin1412/Git-Convention-459389b3e03983d88a5e819b513c11bb


### Notion - Mentoring
#### 멘토링 
- https://www.notion.so/yerin1412/31a389b3e039803bb38ef8a973a4d54b


## 🛠️ Tech Stack

##### Language / Framework
![Java](https://img.shields.io/badge/Java-000000?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring](https://img.shields.io/badge/Springboot-6DB33F?style=for-the-badge&logo=spring&logoColor=white)


##### Database
![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB-4EA94B?style=for-the-badge&logo=mongodb&logoColor=white)

##### Infra
![Redis](https://img.shields.io/badge/Redis-DD0031?style=for-the-badge&logo=redis&logoColor=white)
![Amazon EC2](https://img.shields.io/badge/Amazon_EC2-FF9900?style=for-the-badge&logo=amazon-ec2&logoColor=white)
![Amazon S3](https://img.shields.io/badge/Amazon_S3-569A31?style=for-the-badge&logo=amazon-s3&logoColor=white)

##### CI / CD
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white)
![AWS CodeDeploy](https://img.shields.io/badge/AWS_CodeDeploy-232F3E?style=for-the-badge&logo=amazonaws&logoColor=white)

##### Collaboration Tools
![GitHub](https://img.shields.io/badge/GitHub-100000?style=for-the-badge&logo=github&logoColor=white)
![Notion](https://img.shields.io/badge/Notion-000000?style=for-the-badge&logo=notion&logoColor=white)
![Jira](https://img.shields.io/badge/Jira-0052CC?style=for-the-badge&logo=jira&logoColor=white)
![Discord](https://img.shields.io/badge/Discord-7289DA?style=for-the-badge&logo=discord&logoColor=white)
![Slack](https://img.shields.io/badge/Slack-4A154B?style=for-the-badge&logo=slack&logoColor=white)

##### Monitoring
![Grafana](https://img.shields.io/badge/Grafana-F46800?style=for-the-badge&logo=grafana&logoColor=white)
![Prometheus](https://img.shields.io/badge/Prometheus-E6522C?style=for-the-badge&logo=prometheus&logoColor=white)
![Loki](https://img.shields.io/badge/Loki-F46800?style=for-the-badge&logo=grafana&logoColor=white)

## Architecture
### Infra
<img width="2760" height="1504" alt="image" src="https://github.com/user-attachments/assets/ec412e75-ad15-41f9-8062-e78d4dd43d2b" />


### Monitoring 
<img width="1024" height="559" alt="image" src="https://github.com/user-attachments/assets/dfca9e4b-c6cd-42bb-a0c4-018cec5bc577" />


## 주요 API 도메인

| 도메인 | 기본 경로 | 설명 |
| --- | --- | --- |
| 인증 | `/api/auth` | 사용자/관리자 로그인, 로그아웃 |
| 회선 | `/api/lines` | 회선 조회, 임계치 관리 |
| 가족 | `/api/families` | 가족 구성원 및 가시성 관리 |
| 공유 데이터 | `/api/shared-pools` | 공유 데이터 조회/기여/제한 관리 |
| 권한 | `/api/permissions` | 권한 CRUD |
| 구성원 권한 | `/api/member-permissions` | 구성원별 권한 조회/수정 |
| 역할 | `/api/roles` | 대표 권한 이관 |
| 정책 | `/api/policies` | 사용자 정책 조회/수정 |
| 관리자 정책 | `/api/admin/policies` | 관리자 정책 CRUD 및 활성화 |
| 데이터 | `/api/data` | 데이터 전송, 사용량/잔여량 조회 |
| 앱 | `/api/apps` | 앱 목록 조회 |
| 알림 | `/api/notifications` | 알림 발송, 조회, 읽음 처리 |
| 알림 설정 | `/api/notifications/settings` | 알림 설정 조회/수정 |
| 문의 | `/api/questions` | 문의 등록, 조회, 삭제 |
| 답변 | `/api/answers` | 답변 등록, 삭제 |
| 업로드 | `/api/uploads` | Presigned URL 발급 |
| 트래픽 | `/api/traffic` | 트래픽 요청 적재 |

상세 요청/응답 스펙은 Swagger UI 또는 OpenAPI 문서를 기준으로 확인하는 것을 권장합니다.

## 프로젝트 구조

```text
src/main/java/com/pooli
├─ auth            # 인증/인가
├─ common          # 공통 설정, 예외, 업로드, 보안
├─ line            # 회선
├─ family          # 가족/공유 데이터
├─ permission      # 권한
├─ policy          # 정책
├─ data            # 데이터 사용량
├─ notification    # 알림
├─ question        # 문의/답변
├─ traffic         # Redis Streams 기반 트래픽 처리
├─ monitoring      # 메트릭/테스트용 모니터링
└─ application     # 앱 정보 조회
```

```text
src/main/resources
├─ application.yaml
├─ application-local.yml
├─ application-api.yml
├─ application-traffic.yml
├─ db/migration            # Flyway 마이그레이션
├─ mapper                  # MyBatis XML Mapper
├─ lua/traffic             # 트래픽 차감 Lua 스크립트
└─ monitoring              # Logback 설정
```

## 로컬 실행 방법

### 1. 사전 준비

- Java 21
- MySQL
- Redis
- AWS S3 관련 자격 증명
- `.env` 파일

### 2. 환경 변수

애플리케이션은 `.env` 파일을 읽어 설정을 주입합니다.

예시:

```env
SERVER_PORT=8080

DB_URL=jdbc:mysql://localhost:3306/pooli
DB_USERNAME=root
DB_PASSWORD=password
DB_INIT_FAIL_TIMEOUT=0

FLYWAY_ENABLED=true
FLYWAY_BASELINE_ON_MIGRATE=true

SPRING_SECURITY_USER=admin
SPRING_SECURITY_PASSWORD=admin

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SENTINEL_NODES=
REDIS_SENTINEL_PASSWORD=
SESSION_REDIS_SENTINEL_MASTER=

SESSION_TIMEOUT=30m
SESSION_REDIS_NAMESPACE=pooli:session
SESSION_COOKIE_NAME=JSESSIONID
SESSION_COOKIE_SECURE=false
SESSION_COOKIE_SAME_SITE=lax

APP_CORS_ALLOWED_ORIGINS=http://localhost:3000

AWS_ACCESS_KEY=
AWS_SECRET_KEY=

MONGO_URI=
LOCAL_MONGO_URI=

CACHE_REDIS_HOST=localhost
CACHE_REDIS_PORT=6379
CACHE_REDIS_PASSWORD=
CACHE_REDIS_SENTINEL_MASTER=

STREAMS_REDIS_HOST=localhost
STREAMS_REDIS_PORT=6379
STREAMS_REDIS_PASSWORD=
STREAMS_REDIS_SENTINEL_MASTER=

STREAMS_KEY_TRAFFIC_REQUEST=traffic:deduct:request
STREAMS_GROUP_TRAFFIC=traffic-deduct-cg
STREAMS_CONSUMER_NAME=local-8080
STREAMS_KEY_TRAFFIC_DLQ=traffic:deduct:dlq
```

운영 환경에서는 세션/캐시/스트림 Redis를 분리하고, 쿠키 보안 옵션과 AWS 자격 증명을 환경에 맞게 조정해야 합니다.

### 3. 애플리케이션 실행

Unix/macOS:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows PowerShell:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

프로파일별 실행 예시:

```bash
./gradlew bootRun --args='--spring.profiles.active=api'
./gradlew bootRun --args='--spring.profiles.active=traffic'
```

## 테스트 및 품질 확인

테스트 실행:

```bash
./gradlew test
```

JaCoCo 리포트는 테스트 종료 후 자동 생성됩니다.

산출물 빌드:

```bash
./gradlew bootJar
```

빌드 결과물:

```text
build/libs/pooli.jar
```

## 문서 및 관측 포인트

- Swagger UI: `http://localhost:{SERVER_PORT}/swagger-ui.html`
- OpenAPI Docs: `http://localhost:{SERVER_PORT}/v3/api-docs`
- Health Check: `http://localhost:{SERVER_PORT}/actuator/health`
- Prometheus Metrics: `http://localhost:{SERVER_PORT}/actuator/prometheus`

## 배포 방식

`main` 브랜치에 push되면 GitHub Actions가 다음 순서로 동작합니다.

1. 테스트 실행
2. `bootJar` 빌드
3. Docker 이미지 빌드
4. AWS ECR 푸시
5. CodeDeploy 번들 생성
6. release 그룹 배포
7. traffic 그룹 배포

즉, API 서버와 트래픽 워커를 순차적으로 배포하는 구조입니다.

## 참고 사항

- 데이터베이스 스키마는 Flyway 마이그레이션으로 관리합니다.
- 트래픽 정책과 잔여량 계산은 Redis + Lua 스크립트에 강하게 의존합니다.



