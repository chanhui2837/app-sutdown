# App Shutdown — 백엔드

AppBlock 클론의 서버 부분. MongoDB에 회원 + 차단 스케줄 저장.

## 1. 준비
1. MongoDB Atlas에서 클러스터 생성 → Connect → Connection String 복사
2. 이 폴더에 `.env` 파일 생성 (`.env.example` 복사):
```
PORT=3001
MONGODB_URI=mongodb+srv://.../appshutdown?retryWrites=true&w=majority
JWT_SECRET=긴랜덤문자열
```

## 2. 실행
```bash
npm install
node server.js
# → http://localhost:3001, /api/health 로 확인
```

## 3. API
- `POST /api/auth/signup {username, password, displayName}`
- `POST /api/auth/login {username, password}`
- `GET /api/schedules` (Authorization: Bearer 토큰)
- `POST /api/schedules {name, type, blockedApps[], allowlistMode, dailyLimitMinutes, startTime, endTime, days[], isActive}`
  - type: `DAILY_LIMIT` | `TIME_WINDOW` | `ALWAYS` | `QUICK`
  - TIME_WINDOW 예: `{startTime:"22:00", endTime:"07:00", days:[]}` → 매일 밤 10시~아침 7시
- `PUT /api/schedules/:id` — 수정
- `POST /api/schedules/:id/toggle` — on/off
- `POST /api/schedules/:id/request-delete` — 삭제 요청 (10분 대기 시작, 실제 삭제 아님)
- `POST /api/schedules/:id/confirm-delete` — 10분 지난 후 호출해야 진짜 삭제
- `POST /api/schedules/:id/cancel-delete` — 대기 취소
- `POST /api/usage/report {dateKey:"YYYY-MM-DD", usages:[{packageName, minutes}]}`

## 4. 삭제 10분 규칙 (핵심)
서버가 `pendingDeleteAt`을 기록하고, `confirm-delete`에서
`Date.now() - pendingDeleteAt >= 10분`을 검사합니다.
앱을 지우고 재설치해도 서버에 남아있어 우회가 어렵습니다.
