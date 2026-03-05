# JUnit 단위 테스트 작성 가이드

이 문서는 우리 프로젝트에서 사용하는 JUnit 단위 테스트 작성 요령을 Markdown 형식으로 정리한 문서입니다.

## 1. FIRST 원칙

### F - Fast (빠른 실행)
- 단위 테스트는 매우 빠르게 실행되어야 한다.
- 개발 과정에서 반복 실행이 가능해야 한다.
- 데이터베이스, 네트워크 등 외부 시스템 의존을 제거한다.

### I - Independent (독립성)
- 각 테스트는 다른 테스트에 의존하지 않고 독립적으로 실행되어야 한다.
- 테스트 실행 순서가 결과에 영향을 주어서는 안 된다.

### R - Repeatable (반복 가능)
- 실행 환경이나 실행 시점과 무관하게 항상 동일한 결과를 반환해야 한다.
- 시간, 랜덤 값, 외부 서비스 등 변동 요인에 의존하지 않는다.

### S - Self-Validating (자동 검증)
- 사람이 직접 결과를 확인하지 않아야 한다.
- assertion으로 성공/실패 여부가 자동으로 명확히 판단되어야 한다.

### T - Timely (적절한 시점)
- 테스트는 가능한 한 코드 작성과 동시에 작성한다.
- 이를 통해 설계를 개선하고 오류를 조기에 발견한다.

## 2. JUnit5 주요 어노테이션

| 어노테이션 | 용도 | 설명 |
| --- | --- | --- |
| `@Test` | 테스트 메서드 | 해당 메서드를 테스트 케이스로 실행 |
| `@BeforeEach` | 테스트 전 실행 | 각 테스트 실행 전에 실행되는 메서드 |
| `@AfterEach` | 테스트 후 실행 | 각 테스트 실행 후 실행되는 메서드 |
| `@BeforeAll` | 전체 테스트 전 | 테스트 클래스 시작 시 한 번 실행 |
| `@AfterAll` | 전체 테스트 후 | 테스트 클래스 종료 시 한 번 실행 |
| `@DisplayName` | 테스트 이름 지정 | 테스트 실행 시 표시될 이름 지정 |
| `@Disabled` | 테스트 비활성화 | 해당 테스트를 실행 대상에서 제외 |
| `@Tag` | 테스트 분류 | 테스트를 그룹화하여 특정 테스트만 실행 가능 |
| `@Nested` | 테스트 그룹화 | 내부 클래스로 테스트 구조를 계층화 |
| `@RepeatedTest` | 반복 테스트 | 지정한 횟수만큼 테스트를 반복 실행 |
| `@ParameterizedTest` | 파라미터 테스트 | 하나의 테스트를 여러 입력값으로 실행 |
| `@Timeout` | 실행 시간 제한 | 지정 시간 초과 시 테스트 실패 처리 |
| `@TestInstance` | 인스턴스 라이프사이클 설정 | 테스트 인스턴스 생성 방식 제어 |
| `@TestMethodOrder` | 테스트 실행 순서 지정 | 테스트 실행 순서를 지정 |

## 3. Parameterized Test 관련 어노테이션

| 어노테이션 | 설명 |
| --- | --- |
| `@ValueSource` | 단일 타입 값 배열 제공 |
| `@CsvSource` | CSV 형태 데이터 제공 |
| `@CsvFileSource` | CSV 파일에서 데이터 읽기 |
| `@MethodSource` | 메서드에서 테스트 데이터 제공 |
| `@EnumSource` | Enum 값을 데이터로 사용 |
| `@ArgumentsSource` | 사용자 정의 데이터 제공 |

## 4. Assertion 관련 주요 문법

| 메서드 | 설명 |
| --- | --- |
| `assertEquals(expected, actual)` | 두 값이 같은지 검증 |
| `assertNotEquals(expected, actual)` | 두 값이 다른지 검증 |
| `assertTrue(condition)` | 조건이 `true`인지 검증 |
| `assertFalse(condition)` | 조건이 `false`인지 검증 |
| `assertNull(object)` | 값이 `null`인지 검증 |
| `assertNotNull(object)` | 값이 `null`이 아닌지 검증 |
| `assertThrows(exception, executable)` | 특정 예외 발생 여부 검증 |
| `assertDoesNotThrow(executable)` | 예외가 발생하지 않는지 검증 |
| `assertAll()` | 여러 assertion을 하나의 테스트에서 묶어 실행 |
| `fail()` | 테스트를 강제로 실패 처리 |

## 5. `@ExtendWith(MockitoExtension.class)`

단위 테스트 클래스에 적용한다.

| 항목 | 설명 |
| --- | --- |
| 제공 주체 | Mockito |
| 사용 목적 | Mockito 기능을 JUnit5 테스트에 활성화 |
| 사용 환경 | 순수 단위 테스트 (Spring Context 없음) |
| 동작 방식 | Mockito가 `@Mock`, `@InjectMocks` 등을 자동 초기화 |
| 주요 사용 목적 | Service 단위 테스트 |

### 관련 어노테이션

| 어노테이션 | 대상 | 설명 |
| --- | --- | --- |
| `@Mock` | 필드 | Mockito Mock 객체 생성. 실제 객체 대신 사용하는 가짜 객체 |
| `@InjectMocks` | 필드 | 테스트 대상 객체를 생성하고 `@Mock`/`@Spy` 객체를 자동 주입 |
| `@Spy` | 필드 | 실제 객체 기반으로 일부 메서드만 Mock 처리 가능한 Spy 객체 생성 |
| `@Captor` | 필드 | 메서드 호출 시 전달된 인자를 캡처하기 위한 `ArgumentCaptor` 객체 생성 |

## 6. 작성 체크리스트

- 테스트가 외부 리소스(DB/네트워크/파일시스템)에 의존하지 않는가?
- 테스트 간 순서 의존성이 없는가?
- 동일 입력에 대해 항상 같은 결과를 보장하는가?
- assertion으로 성공/실패가 자동 판별되는가?
- 기능 코드 작성 시점에 테스트를 함께 작성했는가?
