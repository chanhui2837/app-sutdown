# ⛔ App Shutdown (AppBlock 클론)

스스로 정한 규칙으로 앱 사용을 막는 안드로이드 앱 + MongoDB 백엔드.

```
app sutdown/
  backend/   → Node.js + Express + MongoDB (회원/로그인/스케줄/10분삭제)
  android/   → Android Studio 프로젝트 (Kotlin, 빌드 가능)
```

## 질문 답변: 앱을 만들려면 꼭 웹을 먼저 만들어야 하나요?

**아니요. 전혀 필요 없습니다.**

- 지금 이 프로젝트처럼 **안드로이드 네이티브(Kotlin)를 바로 만들면** 됩니다. 웹 과정은 필수가 아닙니다.
- 헷갈리는 이유는 방법이 3가지라서 그렇습니다:

| 방법 | 설명 | 웹 거쳐야 하나? |
|---|---|---|
| 네이티브 (이 프로젝트) | Kotlin으로 안드로이드 앱 직접 개발. 차단 같은 시스템 기능에 필수 | ❌ 불필요 |
| 크로스플랫폼 (Flutter/React Native) | 하나의 코드로 안드로이드+iOS | ❌ 불필요 (Dart/JS이지 웹이 아님) |
| 웹뷰/PWA 래퍼 | 웹을 앱 껍데기에 넣기 | ⭕ 웹이本体. 하지만 **앱 차단은 불가** |

**주의:** 진짜 앱 차단(다른 앱 실행 감지·차단 화면)은 접근성 서비스 + UsageStats 같은 **네이티브 권한**이 필요해서 웹뷰/PWA로는 만들 수 없습니다. 그래서 이 프로젝트는 처음부터 네이티브로 만들었습니다. 백엔드(`backend/`)는 웹이 아니라 **앱이 데이터를 저장하는 서버** 역할(MongoDB)이라 웹 화면이 없습니다.

## 빠른 시작

1. **서버**: `backend/README.md` 보고 `node server.js`
2. **앱**: `android/README.md` 보고 Android Studio에서 `android` 폴더 열기 → Run
3. 앱에서 회원가입 → 권한 2개 허용 → 일정 추가 → 테스트

## 구현된 요구사항
- [x] 회원가입/로그인 (MongoDB + JWT, 비밀번호 해시 저장)
- [x] 하루 사용량 다 쓰면 그날 차단 (DAILY_LIMIT)
- [x] 시간대 차단 (예: 22:00~07:00, 자정 넘김·요일 선택) (TIME_WINDOW)
- [x] 조건 없이 차단 (ALWAYS) + 원터치 즉시 차단 (QUICK)
- [x] 일정 원하는 만큼 추가
- [x] 삭제 버튼 → 10분 대기 → 확정 삭제 (대기 중 차단 유지, 취소 가능)
- [x] AppBlock식 Strict Mode/Cooldown, Allowlist, Quick Block, 스케줄, 사용량 리포트
