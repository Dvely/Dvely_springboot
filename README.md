# Dvely_springboot

AI를 활용하여 웹 서비스를 생성, 수정, 배포까지 자동으로 처리하는 플랫폼입니다.
사용자는 자연어로 요구사항을 입력하면 실시간으로 웹사이트를 생성하고,
변경 사항을 즉시 반영하며 최종적으로 배포할 수 있습니다.

## ☕ Java Version

- Java 25 사용
- Project SDK: 25

### ⚠️ 주의

- Java 25 이상 환경에서 실행을 권장합니다.
- IntelliJ에서 Project SDK를 25로 설정해야 정상 실행됩니다.

## 🏗️ 구조

본 프로젝트는 **DDD + 레이어드 아키텍처**를 기반으로 설계되었으며, 각 레이어는 명확한 책임과 규칙을 가진다.  
이를 통해 **유지보수성, 확장성, 테스트 용이성**을 확보한다.

---

### 1️⃣ Presentation Layer

#### 역할

- HTTP 요청 수신
- Request DTO 파싱
- 인증 사용자 정보 추출
- Application Service 호출
- Response DTO 반환

#### 규칙

- ❌ 비즈니스 로직 작성 금지
- ❌ 외부 API 직접 호출 금지
- ❌ JPA Repository 직접 호출 금지

#### 구성 설명

- controller
  - REST API 진입점
  - ex) ProjectController
- dto
  - request / response 분리
  - ex) CreateProjectRequest, ProjectResponse
- mapper
  - DTO ↔ Application Command/Result 변환
- advice
  - 전역 예외 처리 (@ControllerAdvice)

---

### 2️⃣ Application Layer

#### 역할

- 유스케이스 실행
- 트랜잭션 경계 관리
- 도메인 객체 조합
- 외부 연동 호출 순서 제어
- Command / Query 처리

#### 규칙

- 핵심 비즈니스 로직은 Domain에 위임
- Controller와 Domain 사이의 흐름 조율 담당
- 상태 변경 orchestration 담당

#### 구성 설명

- service
  - command / query 분리
  - ex) ProjectCommandService
- dto
  - 내부 처리용 DTO
  - command / result 구분
- facade
  - 여러 서비스 orchestration (선택)
  - ex) DeploymentFacade
- mapper
  - DTO ↔ Domain 변환
- port
  - in: use case 인터페이스
  - out: 외부 의존성 인터페이스 (Repository, 외부 API)

---

### 3️⃣ Domain Layer

#### 역할

- 핵심 비즈니스 규칙 정의
- Entity
- Value Object
- Aggregate Root
- 도메인 서비스
- 상태 Enum
- Repository 인터페이스

#### 규칙

- ❌ 외부 API를 몰라야 함
- ❌ DTO 의존 금지
- 도메인은 "무엇이 가능한가"를 정의

#### 구성 설명

- entity
  - 핵심 도메인 객체
  - ex) Project, Deployment
- value
  - 값 객체 (불변)
  - ex) ProjectName, DomainName
- enum
  - 상태 관리
  - ex) DeploymentStatus
- aggregate
  - Aggregate Root 정의
- service
  - 도메인 서비스 (Entity로 해결 안 되는 로직)
- repository
  - 인터페이스만 존재
  - ex) ProjectRepository
- event
  - 도메인 이벤트
  - ex) DeploymentStartedEvent

---

### 4️⃣ Infrastructure Layer

#### 역할

- JPA 엔티티 매핑
- Repository 구현체
- 외부 API 클라이언트 (GitHub, Cloudflare 등)
- 외부 응답 데이터 매핑
- 스케줄러 / 비동기 처리
- DB 영속성 처리

#### 규칙

- Domain Layer의 인터페이스를 구현
- 외부 연동 세부사항을 외부에 노출하지 않음

#### 구성 설명

- persistence
  - JPA 관련 구현
  - entity: DB 매핑용 엔티티
  - repository: Domain Repository 구현체
  - mapper: Entity ↔ Domain 변환
- external
  - 외부 API 클라이언트
  - ex) GithubRepositoryClient, CloudflareDnsClient
- config
  - Spring 설정
- scheduler
  - 배치 / cron 작업
- async
  - 비동기 처리

---

### 🔥 핵심 원칙

- 도메인은 외부로부터 **완전히 독립적**이어야 한다.
- Application은 **흐름만 제어**하고, 로직은 Domain에 위임한다.
- Presentation은 **입출력에만 집중**한다.
- Infrastructure는 **구현 세부사항을 캡슐화**한다.

---

## 🛠 Tech Stack

- Spring Boot
- Spring Data JPA
- Validation
- Lombok
- Gradle

## 🚀 주요 기능

- 자연어 기반 웹 서비스 생성
- 실시간 코드 수정 및 반영
- Git 기반 버전 관리
- 프로젝트 자동 배포
- 세션 기반 대화 기록 저장

## 🏗 Architecture

- Domain → Controller → Service → Repository 구조
- AI 요청 처리 레이어 분리
- 비동기 처리 (WebClient / Async)
- DB: 세션 / 프로젝트 / 배포 이력 관리
