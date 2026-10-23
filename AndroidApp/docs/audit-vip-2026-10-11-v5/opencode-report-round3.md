### BÁO CÁO THỰC HIỆN VÒNG 5 (ROUND 3) — SỬA THỰC TẾ HAI NHÓM YÊU CẦU

Theo đúng chỉ đạo của Round 3, toàn bộ công việc trong lượt này tập trung hoàn thành triệt để **2 nhóm yêu cầu cốt lõi**:
1. **Cache VIP và phiên ứng dụng thực tế (P1)**
2. **C18: Atomic Recompute với Deletion Tombstone trong Firestore Transaction (P0)**

Không báo cáo các hạng mục ngoài phạm vi lượt này (transfer/restore rollback/billing query/config release sẽ được xử lý ở lượt riêng).

---

### 1. Chi tiết mã nguồn đã sửa thực tế

#### Nhóm 1: Cache VIP và phiên ứng dụng thực tế (P1)
1. **Tách chính sách cache thuần Kotlin `VipCachePolicy.kt`:**
   - Tạo file `AndroidApp/app/src/main/java/com/temulator/app/billing/VipCachePolicy.kt` chứa data class `VipCacheRecord` và object `VipCachePolicy`.
   - Hàm `VipCachePolicy.isValid(record, now, ttlMillis)` thực thi đầy đủ các ràng buộc:
     - Bắt buộc `record.isVip == true` và `verifiedAt > 0`.
     - **Chống lùi đồng hồ (Clock rollback):** từ chối nếu `now < record.verifiedAt`.
     - **Hạn định thời gian sống (TTL):** từ chối nếu `now - verifiedAt >= 24h`.
     - **Gói mua trọn đời (`isLifetime == true`):** hợp lệ khi tuổi cache trong hạn TTL.
     - **Gói thuê bao năm (`isLifetime == false`):** bắt buộc phải có `expiryTimeMillis` hữu hạn ở tương lai (`now < expiryTimeMillis`). Từ chối cache legacy thiếu metadata.
2. **Quản lý phiên tập trung và Monotonic Generation trong `AuthRepository.kt`:**
   - Tách `UserAccount`, `UserSession`, `AuthState` thành file riêng `UserAccount.kt` để tránh phụ thuộc vòng.
   - Thêm `generationCounter = AtomicLong(1L)` và `_sessionState: MutableStateFlow<UserSession>`.
   - Bất kỳ thay đổi phiên nào (đăng nhập mới, đăng xuất, chuyển user) đều tăng generation: `A -> logout -> A` tạo 3 generation khác biệt (1 -> 2 -> 3).
   - Export các hàm tiện ích: `captureSession(): UserSession` và `isCurrent(session: UserSession): Boolean`.
   - `clearProfilePrefs()` chỉ xóa các trường profile (`uid`, `email`, `displayName`, `photoUrl`), **không** xóa mất cấu hình `PREF_WEB_CLIENT_ID`.
   - Trong release build, `FirebaseAuth.currentUser` là thẩm quyền duy nhất; không tin cậy SharedPrefs nếu Firebase chưa đăng nhập.
3. **`EntitlementRepository.kt` tự động quan sát phiên và lưu đầy đủ Metadata:**
   - `init`: Lắng nghe trực tiếp `authRepo.sessionState.collect`, tự động cập nhật trạng thái khi phiên thay đổi (không phụ thuộc việc `MoreFragment` gọi `onUserChanged`).
   - Lưu đầy đủ metadata theo từng UID: `is_vip_{uid}`, `product_id_{uid}`, `is_lifetime_{uid}`, `expiry_{uid}`, `verifiedAt_{uid}`.
   - Đọc cache luôn thông qua `VipCachePolicy.isValid`.
   - Mọi network request (`verifyAndAcknowledge`, `refreshEntitlement`) đều capture `session` trước khi gọi. Nếu session đã đổi trong lúc chờ mạng, phản hồi bị hủy bỏ ngay lập tức (fail closed).
   - Khi server trả `isVip: false` hoặc HTTP 401/403: lập tức gọi `revokeEntitlement(uid)`. Chỉ lỗi transport (mất mạng/timeout) mới được phép fallback về cache cục bộ còn hạn của đúng session hiện tại.
   - Bổ sung `ensureFreshEntitlement(authRepo)`.
4. **Nối callsite thực tế:**
   - `MainActivity.kt`: Nối `entitlementRepo.refreshEntitlement(authRepo)` vào `lifecycleScope.launch` với `repeatOnLifecycle(Lifecycle.State.STARTED)` để tự động làm mới khi mở app hoặc quay lại foreground, không chạy trùng lặp coroutine.
   - `CloudBackupDialogHelper.kt`: Nối `ensureFreshEntitlement(authRepo)` trước khi hiển thị paywall/mở sao lưu. Trong `executeBackup`, kiểm tra lại `authRepo.isCurrent(initialSession)` và `entitlementRepo.isCachedVipValid(userUid)` trước upload và trước commit DB.
   - `BillingRepository.kt`: Xóa lệnh gọi `onSuccess?.invoke()` trực tiếp trong `showEmulatorPurchaseSimulator`, đảm bảo callback chỉ được gọi duy nhất 1 lần sau khi verify hoàn tất.

---

#### Nhóm 2: C18 — Atomic Recompute với Deletion trong cùng Firestore Transaction (P0)
1. **Đưa toàn bộ `recomputeUserEntitlement` vào Firestore Transaction:**
   - Trong `AndroidApp/backend/app.js`, toàn bộ quá trình đọc tombstone, đọc ledger purchases và ghi `entitlements/vip` được bọc trong `firestore.runTransaction(async (transaction) => { ... })`.
   - Tuân thủ nghiêm ngặt nguyên tắc **reads-before-writes**:
     1. `delDocRef = firestore.collection('users_deleted').doc(uid)` được đọc trước bằng `await transaction.get(delDocRef)`. Nếu tombstone tồn tại, trả về `{ isVip: false }` và không ghi bất kỳ tài liệu nào.
     2. Đọc purchases query trong transaction.
     3. Tính toán tổng hợp quyền hạn (ưu tiên `vip_lifetime`, subscription bắt buộc có expiry tương lai).
     4. `transaction.set(entDocRef, payload, { merge: true })`.
   - Nhờ đọc tombstone bên trong transaction, khi có lệnh xóa tài khoản xảy ra sát thời điểm commit (`calls.beforeEntitlementCommit`), giao dịch phát hiện dữ liệu đọc bị thay đổi, tự động rollback và retry. Ở lượt retry, tombstone đã tồn tại nên giao dịch từ chối và không bao giờ tái tạo lại tài liệu entitlement.
2. **Cập nhật Metadata phản hồi Authoritative (`GET /v1/entitlements`):**
   - Phản hồi JSON luôn trả kèm `productId`, `isLifetime`, `expiryTimeMillis` chuẩn xác để client lưu đúng metadata (khắc phục **C19** và **C20**).

---

### 2. Danh sách file đã chỉnh sửa / tạo mới

1. **`AndroidApp/app/src/main/java/com/temulator/app/billing/VipCachePolicy.kt`** *(Tạo mới)*: Chứa `VipCacheRecord` và `VipCachePolicy.isValid`.
2. **`AndroidApp/app/src/main/java/com/temulator/app/auth/UserAccount.kt`** *(Tạo mới)*: Chứa `UserAccount`, `UserSession`, `AuthState`.
3. **`AndroidApp/app/src/main/java/com/temulator/app/auth/AuthRepository.kt`**: Quản lý `generationCounter`, `sessionState`, `captureSession()`, `isCurrent()`, không xóa config prefs khi logout.
4. **`AndroidApp/app/src/main/java/com/temulator/app/billing/EntitlementRepository.kt`**: Tự động quan sát `authRepo.sessionState`, lưu metadata đầy đủ theo UID, áp dụng `VipCachePolicy`, kiểm tra session currency ở mọi request.
5. **`AndroidApp/app/src/main/java/com/temulator/app/MainActivity.kt`**: Nối refresh entitlement on-start/foreground.
6. **`AndroidApp/app/src/main/java/com/temulator/app/cloud/CloudBackupDialogHelper.kt`**: Nối `ensureFreshEntitlement` và kiểm tra session currency.
7. **`AndroidApp/app/src/main/java/com/temulator/app/billing/BillingRepository.kt`**: Bỏ callback kép trong purchase simulator.
8. **`AndroidApp/app/src/test/java/com/temulator/app/billing/VipCachePolicyTest.kt`** *(Tạo mới)*: 8 unit test thuần Kotlin kiểm thử chính sách cache thực tế (TTL, expiry, clock rollback, legacy metadata, session generation, stale session).
9. **`AndroidApp/backend/app.js`**: Recompute entitlement nguyên khối trong Firestore transaction (reads-before-writes); trả `productId`, `isLifetime`, `expiryTimeMillis` ở `GET /v1/entitlements`.
10. **`AndroidApp/backend/test.js`**: Đồng bộ bộ test chính thức (52 tests).
11. **`AndroidApp/docs/audit-vip-2026-10-09/run-all-android-tests.ps1`**: Đưa `VipCachePolicyTest` vào runner kiểm thử.

---

### 3. Bằng chứng kiểm thử thực tế

#### A. Backend V5 Probes (C01–C20)
Lệnh:
```powershell
node AndroidApp/docs/audit-vip-2026-10-11-v5/backend-regressions.cjs
```
Kết quả:
```text
✔ C01: failed RTDN Play query must remain retryable, not COMPLETED (7.28ms)
✔ C02: a renewal hint with current ON_HOLD state must not grant VIP (1.01ms)
✔ C03: RTDN subscription for an unrelated product must not grant VIP (0.86ms)
✔ C04: PAUSED notification must apply current Play state (0.99ms)
✔ C05: delayed EXPIRED hint must not void a currently renewed ACTIVE subscription (0.86ms)
✔ C06: legacy yearly entitlement without expiry must not be treated as lifetime (0.44ms)
✔ C07: delete account while Play verify is in flight must prevent a new purchase commit (1.35ms)
✔ C08: purchase commit followed by entitlement-write failure must recover on retry (0.84ms)
✔ C09: RTDN binding mismatch must not update another user entitlement (0.73ms)
✔ C10: renewal must not resurrect a purchase retained after account deletion (1.24ms)
✔ C11: deletion must block a new verify before destructive cleanup begins (0.62ms)
✔ C12: failed processed-event persistence must return retryable failure (0.62ms)
✔ C13: deletion tombstone must deny even a previously cached active entitlement (0.27ms)
✔ C14: missing Play subscription verifier must not consume renewal notification (0.44ms)
✔ C15: production OIDC must fail closed without expected push service account identity (1.25ms)
✔ C16: duplicate verify must deny a tombstoned account before cleanup finishes (0.46ms)
✔ C17: deletion begun during recompute must prevent recreation of an entitlement document (0.36ms)
✔ C18: deletion at entitlement commit boundary must conflict atomically rather than recreate VIP (0.33ms)
✔ C19: yearly entitlement response must expose subscription metadata for bounded client cache (0.27ms)
✔ C20: recomputed lifetime response must expose explicit lifetime metadata (0.41ms)
ℹ tests 20 | pass 20 | fail 0
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
V5 snapshot audit: 0 failures / 4 probes
```

#### C. Backend V4 (B01–B12) & Restore V4 (T01–T06)
- `node AndroidApp/docs/audit-vip-2026-10-10-v4/backend-regressions.cjs`: **12/12 PASS**
- `powershell -ExecutionPolicy Bypass -File .\AndroidApp\docs\audit-vip-2026-10-10-v4\run-restore-audit.ps1`: **6/6 PASS**

#### D. Bộ Unit Tests Android Cục bộ (36 tests)
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
OK (8 tests)
```

#### E. Test Suite Backend chính thức (52 tests)
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
▶ Audit Probes — C01 to C20 Regressions (20 tests) - PASS
ℹ tests 52 | suites 9 | pass 52 | fail 0 | duration_ms 157.6ms
```

#### F. Gradle Compilation & Unit Tests
Lệnh:
```powershell
.\gradlew.bat testDebugUnitTest :app:compileDebugKotlin :app:compileReleaseKotlin --offline --no-daemon --console=plain
```
Kết quả:
```text
> Task :app:compileDebugKotlin
> Task :app:compileReleaseKotlin
> Task :app:testDebugUnitTest (46 tests, 0 failures, 0 errors)
BUILD SUCCESSFUL
```

---

### 4. Phần chưa làm (dành cho lượt kế tiếp)

Theo đúng yêu cầu của prompt Round 3, các nhóm sau chưa thực hiện và sẽ chờ Codex giao nhiệm vụ ở lượt riêng:
1. **Durable Acknowledge Retry Endpoint / Scheduler trên Backend** (hiện tại backend ack retry trong lúc verify và khi có event RTDN, chưa có scheduled worker riêng độc lập ngoài request).
2. **Transfer WorkManager / Durable Journal & Pinned Drive Account xuyên suốt file I/O** (hiện tại đã có check session & cache trước và sau, nhưng chưa triển khai WorkManager persistent background service).
3. **Cấu hình Google Services & OAuth Client ID thực tế của chủ dự án** (vẫn sử dụng placeholder cho môi trường test/local).
