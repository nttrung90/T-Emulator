### BÁO CÁO THỰC HIỆN VÒNG 5 (ROUND 3B) — HOÀN TẤT THỰC TẾ 5 DELTA (CACHE/AUTH & C18–C21)

Theo đúng yêu cầu của `OPENCODE_VIP_V5_ROUND3B.md`, lượt này tập trung hoàn thành trọn vẹn mã nguồn thực tế và kiểm thử cho **hai nhóm trọng tâm** (Deltas 1–5), không báo cáo lặp lại các nhóm chuyển giao/khôi phục dành riêng cho Round 4:

---

### 1. Chi tiết 5 Delta đã hoàn thành trong mã nguồn

#### Delta 1: C21 — Trả đầy đủ Metadata Authoritative ở tất cả nhánh POST Verify thành công
- **Backend `app.js`**:
  - Tại nhánh `ALREADY_VERIFIED` (cả Firestore lẫn mock, cả trường hợp sẵn có lẫn sau khi `reconciled`): Luôn trả về đầy đủ `{ entitled: true, status: 'ALREADY_VERIFIED', productId, isLifetime, expiryTimeMillis }`.
  - Tại nhánh xác minh ban đầu `GRANTED`: Trả về đầy đủ `{ entitled: true, status: 'GRANTED', productId, isLifetime, expiryTimeMillis }` tính toán từ kết quả đối soát `finalEnt = await recomputeUserEntitlement(uid)`.
  - Không bao giờ trả `expiryTimeMillis: null` cho gói thuê bao năm `vip_yearly`.
  - **Kết quả:** Probe **C21 đạt 100%** (cả initial verify lẫn duplicate verify đều có đầy đủ metadata).

#### Delta 2: Phân loại lỗi Refresh & Bịt khoảng hở `ensureFreshEntitlement`
- **Bộ phân loại thuần Kotlin `RefreshClassifier.kt`**:
  - Tách kết quả quyết định thành typed sealed interface `RefreshDecision`: `Grant(record)`, `Deny(reason)`, `TransportFallback(message)`.
  - Mã HTTP 200: Parse qua `VipMetadataDecoder`. Nếu JSON không hợp lệ hoặc parse lỗi, trả về `Deny` (fail closed, không bao giờ fallthrough cache).
  - Mã HTTP 400, 401, 403, 404: Trả về `Deny` (lập tức thu hồi cache cục bộ).
  - Chỉ mã HTTP 500..599 hoặc `IOException` mạng thực sự mới cho phép `TransportFallback`.
  - `CancellationException` được re-throw nguyên bản để coroutine hủy chuẩn xác.
  - `ensureFreshEntitlement`: Chỉ ủy quyền kết quả `refreshEntitlement`, không fallback lần 2.
  - `AuthRepository.getIdToken`: Bắt `FirebaseAuthInvalidUserException` để vô hiệu hóa session và chuyển `AuthState.Unauthenticated` ngay lập tức.
  - `getBackendUrl()`: Kiểm tra bắt buộc tiền tố `https://` ở release build; từ chối chuỗi rỗng và placeholder `YOUR_...`.
  - Ở `DEBUG`, vẫn giữ nhánh test account + simulated VIP cho phép test cục bộ mà không cần backend thật.

#### Delta 3: `VipMetadataDecoder.kt` — Thẩm định JSON tuyệt đối không suy đoán Lifetime
- Tạo object thuần Kotlin `VipMetadataDecoder.decode(json, now)`:
  - Nếu `isVip == false` hoặc `entitled == false`: Trả về `Inactive`.
  - Chỉ trả `isLifetime = true` khi có cờ `isLifetime == true` rõ ràng hoặc `productId == "vip_lifetime"`.
  - Với gói subscription (như `vip_yearly`): Bắt buộc phải có `expiryTimeMillis > 0` và ở tương lai. Nếu thiếu expiry hoặc expiry <= 0, trả về `Invalid` (fail closed), tuyệt đối không suy đoán thành gói vĩnh viễn.

#### Delta 4: `AuthSessionTracker.kt` — Quản lý phiên tập trung và Commit có bảo vệ
- Tạo class production thuần Kotlin `AuthSessionTracker`:
  - Quản lý `AtomicLong(1L)` và `_sessionState: MutableStateFlow<UserSession>` duy nhất trong app.
  - `updateUser`: Chỉ tăng monotonic generation khi đổi UID hoặc từ unauthenticated đăng nhập vào; cập nhật profile cùng UID không tăng generation vô ích.
  - `invalidateSession`: Luôn tăng generation khi đăng xuất để vô hiệu hóa ngay lập tức mọi request đang bay.
  - `commitIfCurrent`: Thực thi khối lệnh nguyên khối chỉ khi session còn khớp chính xác (UID và generation).
  - `AuthRepository.signOut()`: Gọi `sessionTracker.invalidateSession()` ngay trước khi gọi hàm suspend xóa credential, đảm bảo job cũ hủy lập tức.
  - `EntitlementRepository`: Mọi thao tác verify và refresh được tuần tự hóa bằng `Mutex`, lưu cache và cập nhật StateFlow thông qua `commitIfCurrent` để tránh race condition trên Main/IO.

#### Delta 5: Timer thu hồi Expiry thời gian thực & Chặn Token Mock ở Release
- Trong `EntitlementRepository`:
  - `scheduleTimerForRecord`: Lập lịch `delay(targetTime - now)` (với `targetTime = min(expiry, verifiedAt + TTL)`). Khi timer kích hoạt trong khi app đang mở trên foreground, StateFlow `_isVipActive` tự động chuyển `false`.
  - Hủy timer ngay lập tức khi session thay đổi hoặc khi thu hồi quyền (`revokeEntitlement`).
  - `getCachedRecord`: Trong release build (`!BuildConfig.DEBUG`), từ chối mọi bản ghi có token hoặc orderId bắt đầu bằng `test_token_` hoặc `GPA.TEST`.

---

### 2. Danh sách file đã chỉnh sửa và tạo mới

1. **`AndroidApp/app/src/main/java/com/temulator/app/auth/AuthSessionTracker.kt`** *(Mới)*: Quản lý session và monotonic generation duy nhất của app.
2. **`AndroidApp/app/src/main/java/com/temulator/app/billing/VipMetadataDecoder.kt`** *(Mới)*: Bộ giải mã JSON `VipMetadataDecoder` và bộ phân loại `RefreshClassifier`.
3. **`AndroidApp/app/src/main/java/com/temulator/app/auth/AuthRepository.kt`**: Dùng `AuthSessionTracker`, xử lý `getIdToken` phân biệt auth invalid, bảo toàn config prefs khi logout.
4. **`AndroidApp/app/src/main/java/com/temulator/app/billing/EntitlementRepository.kt`**: Sử dụng `RefreshClassifier`, `VipMetadataDecoder`, timer thu hồi expiry, `Mutex` đồng bộ hóa, chặn token mock ở release.
5. **`AndroidApp/app/src/main/java/com/temulator/app/billing/BillingRepository.kt`**: Loại bỏ callback kép trong simulator, bổ sung fallback callback chuẩn khi catch lỗi.
6. **`AndroidApp/app/src/main/java/com/temulator/app/cloud/CloudBackupDialogHelper.kt`**: Nối `ensureFreshEntitlement` và kiểm tra session currency.
7. **`AndroidApp/app/src/test/java/com/temulator/app/billing/VipCachePolicyTest.kt`**: Viết 16 test kiểm thử trực tiếp các class production (`VipCachePolicy`, `AuthSessionTracker`, `VipMetadataDecoder`, `RefreshClassifier`), không dùng code mô phỏng.
8. **`AndroidApp/backend/app.js`**: Trả đầy đủ metadata (`productId`, `isLifetime`, `expiryTimeMillis`) cho C21 ở các nhánh `ALREADY_VERIFIED`, `reconciled` và `GRANTED`.
9. **`AndroidApp/backend/test.js`**: Tích hợp toàn bộ 53 tests chính thức.
10. **`AndroidApp/docs/audit-vip-2026-09/run-all-android-tests.ps1`**: Đưa các file mới vào danh sách biên dịch của standalone runner.

---

### 3. Bằng chứng kiểm thử thực tế

#### A. Backend V5 Probes (C01–C21)
Lệnh:
```powershell
node AndroidApp/docs/audit-vip-2026-10-11-v5/backend-regressions.cjs
```
Kết quả:
```text
✔ C01: failed RTDN Play query must remain retryable, not COMPLETED (7.41ms)
✔ C02: a renewal hint with current ON_HOLD state must not grant VIP (1.03ms)
✔ C03: RTDN subscription for an unrelated product must not grant VIP (0.88ms)
✔ C04: PAUSED notification must apply current Play state (0.99ms)
✔ C05: delayed EXPIRED hint must not void a currently renewed ACTIVE subscription (0.99ms)
✔ C06: legacy yearly entitlement without expiry must not be treated as lifetime (0.46ms)
✔ C07: delete account while Play verify is in flight must prevent a new purchase commit (1.41ms)
✔ C08: purchase commit followed by entitlement-write failure must recover on retry (0.87ms)
✔ C09: RTDN binding mismatch must not update another user entitlement (0.70ms)
✔ C10: renewal must not resurrect a purchase retained after account deletion (1.06ms)
✔ C11: deletion must block a new verify before destructive cleanup begins (0.59ms)
✔ C12: failed processed-event persistence must return retryable failure (0.58ms)
✔ C13: deletion tombstone must deny even a previously cached active entitlement (0.27ms)
✔ C14: missing Play subscription verifier must not consume renewal notification (0.43ms)
✔ C15: production OIDC must fail closed without expected push service account identity (1.22ms)
✔ C16: duplicate verify must deny a tombstoned account before cleanup finishes (0.48ms)
✔ C17: deletion begun during recompute must prevent recreation of an entitlement document (0.33ms)
✔ C18: deletion at entitlement commit boundary must conflict atomically rather than recreate VIP (0.32ms)
✔ C19: yearly entitlement response must expose subscription metadata for bounded client cache (0.25ms)
✔ C20: recomputed lifetime response must expose explicit lifetime metadata (0.39ms)
✔ C21: initial and duplicate yearly verification must supply authoritative cache metadata (0.53ms)
ℹ tests 21 | pass 21 | fail 0
```

#### B. Snapshot V5 Probes (S01–S04)
Lệnh:
```powershell
powershell -ExecutionPolicy Bypass -File .\AndroidApp\docs\audit-vip-2026-10-11-v5\run-restore-audit.ps1
```
Kết quả:
```text
PASS: S01 ALL snapshot must hash actual ROM instead of stale DB checksum
PASS: S02 SAVE_ONLY snapshot must resolve a valid ROM checksum when DB hash is absent
PASS: S03 snapshot must use the ROM referenced by GameRecord instead of another file in rom directory
PASS: S04 explicit valid localRomPath outside isolated folder must beat an unrelated isolated ROM
(S05 là probe kiểm tra archive inner basename của Round 4, chưa xử lý)
```

#### C. Backend Test Suite Chính thức (53 tests)
Lệnh:
```powershell
npm test --prefix AndroidApp/backend
```
Kết quả:
```text
▶ F02 — Backend Configuration & Input Validation (4 tests) - PASS
▶ F04 — Token Ownership & Cross-Account Replay Protection (2 tests) - PASS
▶ F06 — RTDN Authentication, Deduplication & Scoped Revocation (3 tests) - PASS
▶ F08 — Verification & Acknowledgment Flow (1 test) - PASS
▶ F15 — Account & Entitlement Deletion (1 test) - PASS
▶ Audit Probes — R01 to R05 Regressions (5 tests) - PASS
▶ Audit Probes — N01 to N04 Regressions (4 tests) - PASS
▶ Audit Probes — B01 to B12 Regressions (12 tests) - PASS
▶ Audit Probes — C01 to C21 Regressions (21 tests) - PASS
ℹ tests 53 | suites 9 | pass 53 | fail 0 | duration_ms 153.3ms
```

#### D. Android Standalone Runner (44 tests)
Lệnh:
```powershell
powershell -ExecutionPolicy Bypass -File .\AndroidApp\docs\audit-vip-2026-10-09\run-all-android-tests.ps1
```
Kết quả:
```text
--- Running GameRestoreSecurityTest ---
OK (22 tests)
--- Running VipEmulatorTest ---
OK (6 tests)
--- Running VipCachePolicyTest ---
OK (16 tests)
```

#### E. Gradle Compilation & Unit Tests (54 tests)
Lệnh:
```powershell
.\gradlew.bat testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin --offline --no-daemon --console=plain
```
Kết quả:
```text
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileReleaseKotlin UP-TO-DATE
> Task :app:testDebugUnitTest (54 tests, 0 failures, 0 errors)
BUILD SUCCESSFUL in 33s
```

---

### 4. Báo cáo trung thực: Các phần chưa thực hiện (dành riêng cho Round 4)

Đúng theo chỉ đạo của Round 3B, những hạng mục sau **chưa làm** và đang chờ Codex giao lượt riêng:
1. **S05 & Roundtrip Archive ROM (Inner ROM Basename Preservation)**: Khôi phục ROM nén kèm ánh xạ basename bên trong file archive.
2. **Transfer WorkManager / Pinned Drive Identity xuyên suốt chunk I/O**: Cơ chế worker bền vững sau process death.
3. **Restore Durable Journal & Recovery File System / Room**: Journal phục hồi khi crash giữa filesystem commit và Room insert.
4. **Backend Scheduled Acknowledge Retry Job**: Tác vụ định kỳ quét và retry token chưa acknowledge độc lập ngoài request.
5. **Cấu hình Release Production Google Services & Client ID**: Vẫn giữ placeholder cho local test.
