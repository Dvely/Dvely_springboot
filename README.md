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

#### 구조

- controller: API 요청 처리
- service: 비즈니스 로직
- repository: DB 접근
- domain: 엔티티

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
