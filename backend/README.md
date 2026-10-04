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
- `POST /api/schedules/:id/toggle` — on/off (앱에서 미사용)
- `DELETE /api/schedules/:id` — 즉시 삭제 (엄격모드 OFF일 때 앱에서 호출)
- `POST /api/usage/report {dateKey:"YYYY-MM-DD", usages:[{packageName, minutes}]}`

## 4. 삭제와 엄격모드
- 삭제는 기본 즉시 삭제(`DELETE /api/schedules/:id`)
- 엄격모드는 앱(단말) 단위 기능: 켜지면 앱에서 삭제 버튼이 막히고,
  끄려면 10분 기다리기를 눌러 10분이 지나야 자동 해제
- (구버전 request/confirm/cancel-delete API는 호환용으로 남아 있음)
