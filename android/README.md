# App Shutdown — Android (Android Studio용)

패키지명: `com.appshutdown.app` / minSdk 26 / Kotlin + Retrofit + WorkManager

## Android Studio에서 열기
1. Android Studio 실행 → **Open** → `android` 폴더 선택 (settings.gradle이 있는 곳)
2. **Sync** 대기 (Gradle 8.7 자동 다운로드)
3. `backend` 서버 먼저 실행 (`node server.js`)
4. 실행 대상:
   - 에뮬레이터: 로그인 화면 서버 주소 `http://10.0.2.2:3001/` 그대로 사용
   - 실기기: PC와 같은 Wi-Fi → PC IP 확인(`ipconfig`) → 예: `http://192.168.0.5:3001/`
5. **Run ▶** (API 26+ 기기/에뮬레이터)

## 최초 설정 순서 (중요)
1. 회원가입 → 로그인
2. 메인 화면 **권한 설정** 버튼:
   - 접근성 → App Shutdown 켜기 (포그라운드 앱 감지 + 차단 화면)
   - 사용 정보 접근 → App Shutdown 허용 (하루 사용시간 측정)
3. **+ 일정 추가**:
   - 시간대 차단: 22:00~07:00 (자정 넘김 지원)
   - 하루 사용량: 예) 유튜브 60분 쓰면 그날 차단
   - 항상 차단 / 즉시 차단
   - 차단 앱 패키지명 입력 (예: `com.instagram.android`, `com.google.android.youtube`)
4. 백그라운드에서도 동작하도록 배터리 최적화 제외 권장

## 삭제와 엄격모드
- 일정 카드 **삭제** → 확인 후 즉시 삭제 (끄기 버튼 없음)
- **🔓 엄격모드 켜기** → 확인 → 켜지면 일정 삭제 불가
- 끄는 방법: **🔒 엄격모드 중 (끄기)** → **⏳ 10분 기다리기** → 10분 지나면 자동 해제
- 엄격모드는 단말에 저장되므로, 앱 삭제 후 재설치하면 풀립니다

## AppBlock에서 가져온 기능 목록
- Quick Block (원터치 즉시 차단)
- 스케줄 (시간대 / 요일 / 자정넘김)
- 사용량 제한 (Daily limit)
- Allowlist 모드 (선택 앱만 허용, 나머지 전부 차단)
- Strict Mode相当: 삭제 쿨다운 10분 + 대기 중 차단 유지 + 서버 강제
- 통계 기반: UsageStatsManager + 서버 `/api/usage/report`
- 재부팅 후 자동 감시 (BootReceiver + 포그라운드 서비스 + WorkManager 동기화)

## 패키지명 찾는 법
- Play 스토어 URL 끝 `id=xxx`가 패키지명
- 대표 예시:
  - 인스타 `com.instagram.android`
  - 유튜브 `com.google.android.youtube`
  - 틱톡 `com.zhiliaoapp.musically`
  - 크롬 `com.android.chrome`
  - 카톡 `com.kakao.talk`
