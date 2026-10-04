// App Shutdown 백엔드 - MongoDB + JWT 인증 + 차단 스케줄
// 실행: npm install 후 .env 설정(MONGODB_URI, JWT_SECRET)하고 node server.js
require("dotenv").config();
const express = require("express");
const cors = require("cors");
const rateLimit = require("express-rate-limit");
const mongoose = require("mongoose");
const bcrypt = require("bcryptjs");
const jwt = require("jsonwebtoken");

const PORT = process.env.PORT || 3001;
const MONGODB_URI = process.env.MONGODB_URI || "";
const JWT_SECRET = process.env.JWT_SECRET || "dev-secret-change-me";
const JWT_EXPIRES = "30d";
// 삭제 확정까지 대기 시간 (요구사항: 10분)
const DELETE_COOLDOWN_MS = 10 * 60 * 1000;

const app = express();
app.use(cors());
app.use(express.json({ limit: "256kb" }));

// ---------- Mongo 모델 ----------
const userSchema = new mongoose.Schema(
  {
    username: { type: String, required: true, unique: true, minlength: 3, maxlength: 20 },
    passwordHash: { type: String, required: true },
    displayName: { type: String, default: "", maxlength: 20 }
  },
  { timestamps: true }
);

// type:
//  - DAILY_LIMIT: 하루에 blockedApps를 N분 쓰면 그날 나머지 시간 차단
//  - TIME_WINDOW: 매일 특정 시간대(예: 22:00~07:00, 자정 넘김 가능)에 차단 + 요일 선택
//  - ALWAYS: 조건 없이 항상 차단 (화이트리스트 allowlist 제외)
//  - QUICK: 즉시 차단 (원터치 포커스 세션)
const scheduleSchema = new mongoose.Schema(
  {
    userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", required: true, index: true },
    name: { type: String, required: true, maxlength: 40, default: "차단 일정" },
    type: {
      type: String,
      required: true,
      enum: ["DAILY_LIMIT", "TIME_WINDOW", "ALWAYS", "QUICK"],
      default: "TIME_WINDOW"
    },
    // 차단할 앱 패키지명 목록 (예: ["com.instagram.android","com.google.android.youtube"])
    // allowlistMode=true 이면 이 목록은 "허용 앱"이 되고 나머지는 전부 차단
    blockedApps: { type: [String], default: [] },
    allowlistMode: { type: Boolean, default: false },
    // DAILY_LIMIT용: 하루 허용 분
    dailyLimitMinutes: { type: Number, default: 60, min: 1, max: 1440 },
    // TIME_WINDOW용: "22:00" 형식, days: 1(월)~7(일), 빈 배열=매일
    startTime: { type: String, default: "22:00" },
    endTime: { type: String, default: "07:00" },
    days: { type: [Number], default: [] },
    isActive: { type: Boolean, default: true },
    strictMode: { type: Boolean, default: true },
    // 삭제 요청 시각 (10분 쿨다운). null이면 삭제대기 아님
    pendingDeleteAt: { type: Date, default: null }
  },
  { timestamps: true }
);

// 하루 사용량 기록 (DAILY_LIMIT 판정용, 앱에서 주기적으로 보고)
const usageSchema = new mongoose.Schema(
  {
    userId: { type: mongoose.Schema.Types.ObjectId, ref: "User", required: true, index: true },
    packageName: { type: String, required: true },
    dateKey: { type: String, required: true }, // "2026-10-03" (디바이스 로컬 날짜 기준)
    minutes: { type: Number, default: 0, min: 0 }
  },
  { timestamps: true }
);
usageSchema.index({ userId: 1, packageName: 1, dateKey: 1 }, { unique: true });

const User = mongoose.model("User", userSchema);
const Schedule = mongoose.model("Schedule", scheduleSchema);
const Usage = mongoose.model("Usage", usageSchema);

let dbReady = false;
async function connectDB() {
  if (!MONGODB_URI) {
    console.log("[mongo] MONGODB_URI 없음 - API는 503 (.env 확인)");
    return;
  }
  try {
    await mongoose.connect(MONGODB_URI);
    dbReady = true;
    console.log("[mongo] 연결 성공");
  } catch (e) {
    console.error("[mongo] 연결 실패:", e.message);
  }
}
function needDB(req, res, next) {
  if (!dbReady) return res.status(503).json({ error: "DB 미연결(MONGODB_URI 확인)" });
  next();
}

// ---------- 인증 ----------
const USER_RE = /^[A-Za-z0-9가-힣_]{3,20}$/;
const TIME_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;
function signToken(user) {
  return jwt.sign({ uid: String(user._id), u: user.username }, JWT_SECRET, { expiresIn: JWT_EXPIRES });
}
function auth(req, res, next) {
  const h = req.headers.authorization || "";
  const t = h.startsWith("Bearer ") ? h.slice(7) : null;
  if (!t) return res.status(401).json({ error: "로그인 필요" });
  try {
    req.me = jwt.verify(t, JWT_SECRET);
    next();
  } catch (e) {
    return res.status(401).json({ error: "토큰 만료/무효 - 다시 로그인" });
  }
}
const authLimiter = rateLimit({ windowMs: 15 * 60 * 1000, max: 100 });

function toClientSchedule(s) {
  const o = s.toObject ? s.toObject() : s;
  let remainingSec = 0;
  if (o.pendingDeleteAt) {
    remainingSec = Math.max(
      0,
      Math.ceil((new Date(o.pendingDeleteAt).getTime() + DELETE_COOLDOWN_MS - Date.now()) / 1000)
    );
    // 쿨다운이 이미 지났으면 프론트에서 "삭제 확정 가능"으로 표시
    if (remainingSec <= 0) remainingSec = 0;
  }
  return { ...o, deleteRemainingSec: remainingSec, deleteCooldownSec: DELETE_COOLDOWN_MS / 1000 };
}

function validateScheduleBody(b) {
  const out = {};
  if (b.name !== undefined) {
    if (typeof b.name !== "string" || !b.name.trim()) return { error: "일정 이름 필요" };
    out.name = b.name.trim().slice(0, 40);
  }
  if (b.type !== undefined) {
    if (!["DAILY_LIMIT", "TIME_WINDOW", "ALWAYS", "QUICK"].includes(b.type))
      return { error: "type은 DAILY_LIMIT/TIME_WINDOW/ALWAYS/QUICK 중 하나" };
    out.type = b.type;
  }
  if (b.blockedApps !== undefined) {
    if (!Array.isArray(b.blockedApps)) return { error: "blockedApps는 배열" };
    out.blockedApps = b.blockedApps.map((x) => String(x).trim()).filter(Boolean).slice(0, 100);
  }
  if (b.allowlistMode !== undefined) out.allowlistMode = !!b.allowlistMode;
  if (b.dailyLimitMinutes !== undefined) {
    const n = Number(b.dailyLimitMinutes);
    if (!Number.isFinite(n) || n < 1 || n > 1440) return { error: "dailyLimitMinutes는 1~1440" };
    out.dailyLimitMinutes = Math.floor(n);
  }
  if (b.startTime !== undefined) {
    if (!TIME_RE.test(String(b.startTime))) return { error: "startTime은 HH:MM" };
    out.startTime = b.startTime;
  }
  if (b.endTime !== undefined) {
    if (!TIME_RE.test(String(b.endTime))) return { error: "endTime은 HH:MM" };
    out.endTime = b.endTime;
  }
  if (b.days !== undefined) {
    if (!Array.isArray(b.days)) return { error: "days는 배열(1~7)" };
    const ds = b.days.map(Number).filter((d) => d >= 1 && d <= 7);
    out.days = [...new Set(ds)];
  }
  if (b.isActive !== undefined) out.isActive = !!b.isActive;
  if (b.strictMode !== undefined) out.strictMode = !!b.strictMode;
  return { out };
}

// ---------- 라우트 ----------
// 회원가입
app.post("/api/auth/signup", authLimiter, needDB, async (req, res) => {
  try {
    const { username, password, displayName } = req.body || {};
    if (!username || !USER_RE.test(username))
      return res.status(400).json({ error: "아이디는 3~20자(한글/영문/숫자/_)" });
    if (!password || String(password).length < 6)
      return res.status(400).json({ error: "비밀번호 6자 이상" });
    const exists = await User.findOne({ username });
    if (exists) return res.status(409).json({ error: "이미 있는 아이디" });
    const passwordHash = await bcrypt.hash(String(password), 10);
    const u = await User.create({
      username,
      passwordHash,
      displayName: String(displayName || username).slice(0, 20)
    });
    res.json({
      token: signToken(u),
      user: { username: u.username, displayName: u.displayName || u.username }
    });
  } catch (e) {
    res.status(500).json({ error: "가입 실패: " + e.message });
  }
});

// 로그인
app.post("/api/auth/login", authLimiter, needDB, async (req, res) => {
  try {
    const { username, password } = req.body || {};
    const u = await User.findOne({ username });
    if (!u) return res.status(401).json({ error: "아이디/비밀번호 확인" });
    const ok = await bcrypt.compare(String(password || ""), u.passwordHash);
    if (!ok) return res.status(401).json({ error: "아이디/비밀번호 확인" });
    res.json({
      token: signToken(u),
      user: { username: u.username, displayName: u.displayName || u.username }
    });
  } catch (e) {
    res.status(500).json({ error: "로그인 실패: " + e.message });
  }
});

app.get("/api/me", needDB, auth, async (req, res) => {
  const u = await User.findById(req.me.uid);
  if (!u) return res.status(404).json({ error: "유저 없음" });
  res.json({ user: { username: u.username, displayName: u.displayName || u.username } });
});

// 일정 목록
app.get("/api/schedules", needDB, auth, async (req, res) => {
  const list = await Schedule.find({ userId: req.me.uid }).sort({ createdAt: -1 });
  res.json({ schedules: list.map(toClientSchedule) });
});

// 일정 추가 (원하는 만큼 여러 개 가능)
app.post("/api/schedules", needDB, auth, async (req, res) => {
  const { error, out } = validateScheduleBody(req.body || {});
  if (error) return res.status(400).json({ error });
  if (!out.name) out.name = "차단 일정";
  if (!out.type) out.type = "TIME_WINDOW";
  try {
    const s = await Schedule.create({ userId: req.me.uid, ...out });
    res.json({ schedule: toClientSchedule(s) });
  } catch (e) {
    res.status(500).json({ error: "일정 생성 실패: " + e.message });
  }
});

// 일정 수정 (삭제대기 중에도 수정은 가능 - 단 strictMode면 앱에서 막는 것을 권장)
app.put("/api/schedules/:id", needDB, auth, async (req, res) => {
  const { error, out } = validateScheduleBody(req.body || {});
  if (error) return res.status(400).json({ error });
  const s = await Schedule.findOne({ _id: req.params.id, userId: req.me.uid });
  if (!s) return res.status(404).json({ error: "일정 없음" });
  Object.assign(s, out);
  await s.save();
  res.json({ schedule: toClientSchedule(s) });
});

// 활성 토글 (빠른 on/off)
app.post("/api/schedules/:id/toggle", needDB, auth, async (req, res) => {
  const s = await Schedule.findOne({ _id: req.params.id, userId: req.me.uid });
  if (!s) return res.status(404).json({ error: "일정 없음" });
  s.isActive = !s.isActive;
  await s.save();
  res.json({ schedule: toClientSchedule(s) });
});

// 1단계: 삭제 요청 -> 10분 쿨다운 시작 (이때는 실제로 안 지워짐)
app.post("/api/schedules/:id/request-delete", needDB, auth, async (req, res) => {
  const s = await Schedule.findOne({ _id: req.params.id, userId: req.me.uid });
  if (!s) return res.status(404).json({ error: "일정 없음" });
  if (s.pendingDeleteAt) {
    return res.json({ schedule: toClientSchedule(s), message: "이미 삭제 대기 중" });
  }
  s.pendingDeleteAt = new Date();
  await s.save();
  res.json({ schedule: toClientSchedule(s), message: "10분 뒤에 삭제 확정 가능" });
});

// 삭제 대기 취소 (10분 안에 마음 바꾸면 복구)
app.post("/api/schedules/:id/cancel-delete", needDB, auth, async (req, res) => {
  const s = await Schedule.findOne({ _id: req.params.id, userId: req.me.uid });
  if (!s) return res.status(404).json({ error: "일정 없음" });
  s.pendingDeleteAt = null;
  await s.save();
  res.json({ schedule: toClientSchedule(s) });
});

// 2단계: 10분 지난 후 확정 삭제
app.post("/api/schedules/:id/confirm-delete", needDB, auth, async (req, res) => {
  const s = await Schedule.findOne({ _id: req.params.id, userId: req.me.uid });
  if (!s) return res.status(404).json({ error: "일정 없음" });
  if (!s.pendingDeleteAt)
    return res.status(400).json({ error: "먼저 삭제 요청(삭제 버튼)을 눌러야 합니다" });
  const elapsed = Date.now() - new Date(s.pendingDeleteAt).getTime();
  if (elapsed < DELETE_COOLDOWN_MS) {
    const remain = Math.ceil((DELETE_COOLDOWN_MS - elapsed) / 1000);
    return res.status(400).json({ error: `아직 ${Math.floor(remain / 60)}분 ${remain % 60}초 남음`, remainingSec: remain });
  }
  await Schedule.deleteOne({ _id: s._id });
  res.json({ ok: true });
});

// 즉시 삭제 (엄격모드 OFF일 때 앱에서 사용)
app.delete("/api/schedules/:id", needDB, auth, async (req, res) => {
  const s = await Schedule.findOne({ _id: req.params.id, userId: req.me.uid });
  if (!s) return res.status(404).json({ error: "일정 없음" });
  await Schedule.deleteOne({ _id: s._id });
  res.json({ ok: true });
});

// 사용량 보고 (안드로이드가 주기적으로 전송: {dateKey, usages:[{packageName, minutes}]})
app.post("/api/usage/report", needDB, auth, async (req, res) => {
  try {
    const { dateKey, usages } = req.body || {};
    if (!dateKey || !/^\d{4}-\d{2}-\d{2}$/.test(String(dateKey)))
      return res.status(400).json({ error: "dateKey YYYY-MM-DD 필요" });
    if (!Array.isArray(usages)) return res.status(400).json({ error: "usages 배열 필요" });
    for (const u of usages.slice(0, 100)) {
      if (!u.packageName) continue;
      const m = Math.max(0, Number(u.minutes) || 0);
      await Usage.findOneAndUpdate(
        { userId: req.me.uid, packageName: String(u.packageName), dateKey },
        { $set: { minutes: m } },
        { upsert: true }
      );
    }
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: "사용량 저장 실패: " + e.message });
  }
});

// 오늘 사용량 조회
app.get("/api/usage/today", needDB, auth, async (req, res) => {
  const dateKey = req.query.dateKey || new Date().toISOString().slice(0, 10);
  const list = await Usage.find({ userId: req.me.uid, dateKey }).select("packageName minutes -_id");
  res.json({ dateKey, usages: list });
});

app.get("/api/health", (req, res) => {
  res.json({ ok: true, db: dbReady ? "connected" : "disconnected", time: new Date().toISOString() });
});

connectDB().then(() => app.listen(PORT, () => console.log("[server] http://localhost:" + PORT)));
