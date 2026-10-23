# Báo cáo Vòng lặp Kiểm thử & Hoàn tất VIP / Cloud (Antigravity Handoff)
**Ngày thực hiện:** 2026-10-11  
**Tác nhân thực hiện:** Antigravity (Google DeepMind Agentic Assistant)  
**Workspace:** `F:/AI/T-Emulator`  
**Android Root:** `F:/AI/T-Emulator/AndroidApp`  
**JDK Môi trường:** JBR 17 (`C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe`)

---

## 1. Tóm tắt Tổng thể (Executive Summary)

Antigravity đã tiếp nhận toàn bộ công việc kiểm toán và khắc phục lỗi VIP/Cloud từ phiên Codex/OpenCode theo hồ sơ bàn giao `ANTIGRAVITY_VIP_HANDOFF_2026-10-11.md` và `VIP_AUDIT_STATUS_2026-10-11.md`. Toàn bộ các yêu cầu trong **Round 3C**, **Round 4**, và **Round 5** đã được hoàn thành triệt để trên mã nguồn production thực tế, kiểm tra lặp với các bộ test có sẵn (không thay đổi bất kỳ assertion nào), và xác minh toàn diện.

### Bảng Kết quả Kiểm toán So sánh (Before vs After)

| Bộ kiểm tra (Test Suite) | Trạng thái Bàn giao | Trạng thái Sau khi Antigravity hoàn tất | Kết quả chi tiết |
|---|---|---|---|
| **Backend Unit Tests** (`npm test --prefix backend`) | 53/53 PASS | **58/58 PASS** | Đạt 100% (+5 tests `R5-01` đến `R5-05` cho Round 5 Acknowledge Retry Job) |
| **Backend Regressions** (`backend-regressions.cjs`) | 21/21 PASS | **21/21 PASS** | `C01` - `C21` đạt 100% |
| **Backend Acknowledge Audit** (`backend-ack-audit.cjs`) | 0/1 FAIL (`C22`) | **1/1 PASS** | `C22` đạt 100% (nâng throw khi thiếu Play API, không ghi nhận false success) |
| **VIP Policy / Session Audit** (`run-policy-audit.ps1`) | 20/20 PASS | **20/20 PASS** | `P01` - `P15`, `Q01` - `Q05` đạt 100% |
| **Snapshot / Restore / Rollback Audit** (`run-restore-audit.ps1`) | 4/6 PASS (`S05`, `S06` FAIL) | **6/6 PASS** | `S01` - `S06` đạt 100% (`S05` archive inner-ROM & `S06` rollback safe-guard) |
| **Android Unit Tests** (`:app:testDebugUnitTest`) | 55/57 PASS (2 fail) | **68/68 PASS** (0 failures, 0 errors) | Sửa 2 integration fail, thêm `GameOperationCoordinatorTest` (3 tests) & `BillingQueryReducerTest` (8 tests) |
| **Android Release Compilation** (`:app:compileReleaseKotlin`) | PASS | **PASS (BUILD SUCCESSFUL)** | Biên dịch release sạch sẽ, không lỗi cú pháp hay dependencies |
| **Release Config Validation** (`:app:validateReleaseConfig`) | Chưa có task | **PASS / FAIL-CLEAR** | Đạt khi cấu hình đủ; fail-closed rõ ràng tên key (che giá trị) khi thiếu cấu hình |

---

## 2. Chi tiết Sửa đổi 5 Lỗi Tái hiện Ban đầu (Round 3C)

1. **Bỏ qua lỗi cấu hình bằng cache (`missingBackendConfigurationMustNotBecomeAValidCachedCloudGrant` - FAIL -> PASS)**:
   - *Tệp sửa:* `AndroidApp/app/src/main/java/com/temulator/app/billing/EntitlementRepository.kt`
   - *Nguyên nhân:* Hàm `ensureFreshEntitlement` khi gặp lỗi tải (`refresh = false`) đã fallback về cache lần thứ 2 mà không kiểm tra loại lỗi từ chối cấu hình/auth.
   - *Khắc phục:* Loại bỏ khối fallback cache thứ hai; chỉ cho phép cache hợp lệ khi lỗi mạng tạm thời (`isTemporaryServerFailure == true`) và thời gian hợp lệ chưa quá hạn; các trường hợp `ConfigError`, `Unauthenticated`, `Revoked` lập tức trả về `VipStatusResult.Denial`.

2. **Metadata UID khác làm đổi flow VIP hiện tại (`writingOtherUsersMetadataMustNotGrantCurrentUser` - FAIL -> PASS)**:
   - *Tệp sửa:* `AndroidApp/app/src/main/java/com/temulator/app/billing/EntitlementRepository.kt`
   - *Nguyên nhân:* `saveEntitlementMetadata` nhận `userUid` tùy ý và cập nhật trực tiếp `_vipStatusFlow`, dẫn đến việc ghi đè trạng thái VIP của người dùng hiện tại bằng metadata của người dùng khác.
   - *Khắc phục:* Áp dụng `authRepo.commitIfCurrent(session) { ... }` và kiểm tra khớp UID/generation phiên hiện tại trước khi ghi vào SharedPreferences và cập nhật `_vipStatusFlow`. Khi UID không khớp, chỉ lưu trữ độc lập theo scoped key của UID đó mà tuyệt đối không thay đổi flow VIP của phiên đang hoạt động.

3. **Restore archive sai tên save native (`S05` - FAIL -> PASS)**:
   - *Tệp sửa:* `AndroidApp/app/src/main/java/com/temulator/app/cloud/GameRestoreService.kt` & `GameStorageManager.kt` & `GameSnapshotService.kt`
   - *Nguyên nhân:* Quá trình restore tập tin nén (.zip / .7z) sử dụng tên gốc của tệp lưu trữ thay vì phân giải tên ROM con (inner ROM member), làm sai lệch tên file `.sav` và `.mss` do `VirtualFile.GetFileName` mong đợi.
   - *Khắc phục:* Bổ sung `resolveArchiveMember(...)` trong `GameStorageManager`; inject thông tin `"archiveMember"` vào snapshot manifest; `GameRestoreService` phân giải chính xác inner ROM member name trước khi phục hồi file save và state.

4. **Rollback nguồn thiếu vẫn xóa dữ liệu sống (`S06` - FAIL -> PASS)**:
   - *Tệp sửa:* `AndroidApp/app/src/main/java/com/temulator/app/storage/GameStorageManager.kt` & `GameRestoreService.kt`
   - *Nguyên nhân:* `restoreFromRollbackBackup` xóa sạch thư mục mục tiêu trước khi kiểm tra sự tồn tại của thư mục backup nguồn, làm mất dữ liệu save đang hoạt động nếu backup bị thiếu.
   - *Khắc phục:* Kiểm tra nghiêm ngặt `sourceBackupDir.exists()` và `isDirectory` trước bất kỳ thao tác xóa nào. Nếu nguồn không tồn tại, lập tức trả về `false` và giữ nguyên toàn bộ dữ liệu live saves; đồng thời tạo marker `RECOVERY_REQUIRED.marker` để phục hồi ngoại lệ nếu có sự cố.

5. **Thiếu Google Play Acknowledge API bị coi là thành công (`C22` - FAIL -> PASS)**:
   - *Tệp sửa:* `AndroidApp/backend/app.js`
   - *Nguyên nhân:* `acknowledgePlayPurchase` trả về `undefined` khi SDK API thiếu, làm logic gọi verify tiếp tục lưu `acknowledgementState = 1`.
   - *Khắc phục:* Ném ngoại lệ `Error("Play product/subscription acknowledgement API unavailable")` khi API thiếu. Endpoint verify bắt ngoại lệ này, giữ nguyên quyền entitlement cho người dùng nhưng duy trì `acknowledgementState = 0` và lên lịch retry bền vững thay vì ghi nhận thành công giả tạo. Tuyệt đối không fallback sang products acknowledge endpoint cho gói thuê bao subscription.

---

## 3. Triển khai Round 4 (Cloud Identity, Durable Transfer, Recovery, Coordinator)

1. **Bộ điều phối tài nguyên trò chơi (`GameOperationCoordinator.kt`)**:
   - Hiện thực hóa cơ chế khóa coroutine không chặn luồng (`Semaphore(1)`) quản lý tranh chấp:
     - `tryAcquire(gameId, OperationType)` với các loại `PLAYING`, `SNAPSHOT`, `RESTORE`.
     - `acquireGlobalRestoreLock()` nhằm tuần tự hóa tất cả các tác vụ restore trên toàn bộ ứng dụng.
     - `isGameBusy(gameId)` cho phép UI kiểm tra trạng thái trước khi mở game hoặc sao lưu.
   - Tích hợp khóa vào `EmulatorActivity` (khi khởi động và thoát game), `GameSnapshotService`, và `GameRestoreService`.

2. **Gắn chặt phiên định danh Drive (`DriveAuthorizationManager.kt` & `DriveBackupRepository.kt`)**:
   - Thêm `generationCounter`, `captureSession()`, `isCurrent(session)`.
   - Mọi thao tác tải lên, phân mảnh (chunk streaming) và cam kết metadata của Drive đều yêu cầu `pinnedSession: DriveSession?`. Nếu tài khoản Google Drive thay đổi hoặc bị ngắt kết nối giữa chừng, toàn bộ luồng truyền dữ liệu sẽ hủy ngay lập tức mà không ghi đè dữ liệu sai tài khoản.

3. **Nhật ký truyền tải bền vững & Giao dịch Room (`CloudBackupDialogHelper.kt`)**:
   - Tạo nhật ký `transfer_journal.json` trước khi bắt đầu tải lên hoặc tải về.
   - Sử dụng `db.withTransaction { ... }` (Room ktx coroutine-safe) để cam kết trạng thái cơ sở dữ liệu Room một cách nguyên tử.
   - Hiện thực `reconcileOrphanTransfers(...)` để dọn dẹp các thư mục tạm và tập tin mồ côi nếu ứng dụng bị crash giữa chừng.

4. **Khôi phục Intent OAuth Drive (`GamesFragment.kt`)**:
   - Lưu trữ `PendingDriveAction(intent, appUid, appGeneration, game)` khi mở yêu cầu cấp quyền Google Drive OAuth.
   - Khi Activity nhận kết quả OAuth trả về, xác minh phiên ứng dụng và khôi phục đúng tác vụ sao lưu hoặc danh sách khôi phục tương ứng.

5. **Xác nhận xóa tài khoản bảo vệ phiên (`MoreFragment.kt`)**:
   - Ghim `AuthSession` tại thời điểm mở hộp thoại xác nhận xóa tài khoản.
   - Kiểm tra phiên hợp lệ trước khi lấy token, trước khi gọi HTTP `DELETE /v1/account`, và sau khi nhận phản hồi từ server. Ngăn chặn triệt để tình trạng xóa nhầm phiên người dùng mới nếu có sự thay đổi tài khoản trong lúc chờ người dùng xác nhận.

---

## 4. Triển khai Round 5 (Billing Lifecycle, Release Validation, Durable Acknowledge Retry)

1. **Vòng đời Billing & Typed Query (`BillingQueryReducer.kt` & `BillingRepository.kt`)**:
   - Tách biệt logic rút gọn truy vấn vào `BillingQueryReducer.kt` với các trạng thái tường minh: `BillingQueryResult.Verified`, `NotOwned`, `Pending`, `Error`.
   - `PendingPurchaseAction`: Giới hạn hành động callback thành công theo UID và generation phiên (`consumeIfMatches`), đảm bảo thực thi chính xác một lần (exactly-once) qua `AtomicBoolean`.
   - Hàm `checkPreflight(backendUrl, userIsSignedIn)`: Kiểm tra hợp lệ trước khi gọi Play Flow; từ chối gọi Play Store nếu thiếu URL backend HTTPS hoặc người dùng chưa đăng nhập.
   - Giữ lại debug simulator chỉ dành riêng cho cấu hình `DEBUG`.

2. **Khởi tạo Bootstrap Ứng dụng & Cấu hình Release (`TemulatorApplication.kt` & Gradle)**:
   - Tạo `TemulatorApplication` đăng ký trong `AndroidManifest.xml`.
   - Khởi tạo lập trình `FirebaseApp` thông qua `FirebaseOptions` từ `BuildConfig` khi ứng dụng chưa có tệp `google-services.json`.
   - Tự động chạy nền dọn dẹp phân mảnh mồ côi `reconcileOrphanTransfers` và phục hồi khôi phục `recoverPendingRestores` khi khởi động ứng dụng.
   - Viết Gradle task `validateReleaseConfig` trong `app/build.gradle.kts` kết nối trước `assembleRelease` và `bundleRelease`:
     - Kiểm tra đầy đủ: `GOOGLE_WEB_CLIENT_ID`, `BACKEND_VERIFY_URL` (bắt buộc HTTPS), `FIREBASE_API_KEY`, `FIREBASE_APPLICATION_ID`, `FIREBASE_PROJECT_ID`.
     - Khi thiếu cấu hình: Báo lỗi rõ ràng danh sách tên key bị thiếu/không hợp lệ, che giấu (redact) toàn bộ giá trị nhạy cảm để không lộ trên log CI/CD.

3. **Job Phục hồi Xác nhận Giao dịch Phía Máy chủ (`POST /v1/jobs/acknowledge-retry`)**:
   - *Tệp sửa:* `AndroidApp/backend/app.js`, `server.js`, `test.js`
   - *Mã hóa Token:* Hiện thực AES-256-GCM (`encryptToken`, `decryptToken`) với `ACK_TOKEN_ENCRYPTION_KEY`. Trong môi trường production, nếu thiếu khóa mã hóa server sẽ fail-closed ngay lập tức.
   - *Lưu trữ bền vững:* Lưu tác vụ xác nhận chưa hoàn thành vào collection `pending_acks` với token đã mã hóa, UID sở hữu, ID sản phẩm, số lần thử và thời gian backoff.
   - *Job Endpoint Bảo vệ:* `POST /v1/jobs/acknowledge-retry` được bảo vệ bằng `authenticateJobRunner` (từ chối token người dùng Firebase, chấp nhận Google Cloud OIDC Service Account hoặc shared secret `X-Job-Secret`).
   - *Kiểm soát Đồng thời & An toàn:* Áp dụng lease trạng thái `IN_PROGRESS` có thời hạn để chống duplicate worker; kiểm tra tombstone tài khoản đã xóa; kiểm tra đơn hàng voided; truy vấn lại Play Store và gọi `acknowledgePlayPurchase`.
   - *Thêm bộ test:* Bổ sung 5 kịch bản kiểm tra tự động `R5-01` đến `R5-05` chứng minh khả năng phục hồi sau khi khởi động lại server mà không cần client gọi lại.

---

## 5. Nhật ký Lệnh Kiểm tra Thực tế (Verification Commands & Logs)

### 1. Backend NPM Test Suite
```bash
npm test --prefix backend
```
- **Kết quả:** 58 tests passed, 0 failed, 9 suites, exit code 0.
- **Bao gồm:** F15 Account Deletion, R01-R05, N01-N04, B01-B12, C01-C21, và R5-01-R5-05.

### 2. Backend Regressions Audit
```bash
node docs/audit-vip-2026-10-11-v5/backend-regressions.cjs
```
- **Kết quả:** 21 tests passed (C01 đến C21), 0 failed, exit code 0.

### 3. Backend Acknowledge Audit
```bash
node docs/audit-vip-2026-10-11-v5/backend-ack-audit.cjs
```
- **Kết quả:** 1 test passed (C22), 0 failed, exit code 0.

### 4. VIP Policy & Session Audit
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docs/audit-vip-2026-10-11-v5/run-policy-audit.ps1
```
- **Kết quả:** 20 probes passed (P01-P15, Q01-Q05), 0 failures, exit code 0.

### 5. Snapshot & Restore Audit
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docs/audit-vip-2026-10-11-v5/run-restore-audit.ps1
```
- **Kết quả:** 6 probes passed (S01-S06), 0 failures, exit code 0.

### 6. Android Unit Tests
```powershell
& 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest --offline --no-daemon --console=plain
```
- **Kết quả:** BUILD SUCCESSFUL in 22s.
- **Chi tiết XML:**
  - `BillingQueryReducerTest`: 8 passed, 0 failed
  - `EntitlementRepositoryIntegrationAuditTest`: 3 passed, 0 failed
  - `VipCachePolicyTest`: 16 passed, 0 failed
  - `VipEmulatorTest`: 6 passed, 0 failed
  - `GameOperationCoordinatorTest`: 3 passed, 0 failed
  - `GameRestoreSecurityTest`: 22 passed, 0 failed
  - `GameRepositoryAntiCollisionTest`: 3 passed, 0 failed
  - `GameSearchNormalizerTest`: 5 passed, 0 failed
  - `GameStorageManagerTest`: 2 passed, 0 failed
  - **Tổng cộng:** 68/68 passed, 0 failures, 0 errors.

### 7. Android Release Kotlin Compilation
```powershell
& 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:compileReleaseKotlin --offline --no-daemon --console=plain
```
- **Kết quả:** BUILD SUCCESSFUL in 16s.

### 8. Release Config Validation Task
```powershell
# Chạy với properties hợp lệ (kiểm tra pass):
& 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:validateReleaseConfig '-PGOOGLE_WEB_CLIENT_ID=test-client.apps.googleusercontent.com' '-PBACKEND_VERIFY_URL=https://test.backend.example.com' '-PFIREBASE_API_KEY=AIzaSyTestApiKey123' '-PFIREBASE_APPLICATION_ID=1:123456789:android:abcdef' '-PFIREBASE_PROJECT_ID=test-project' --offline --no-daemon --console=plain
# Output: BUILD SUCCESSFUL. Release configuration validation passed: all required keys are present.

# Chạy không truyền properties (kiểm tra fail-clear an toàn):
& 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:validateReleaseConfig --offline --no-daemon --console=plain
# Output: BUILD FAILED. Release configuration validation failed. The following required keys are missing or invalid: GOOGLE_WEB_CLIENT_ID, BACKEND_VERIFY_URL (must be HTTPS URL), FIREBASE_API_KEY, FIREBASE_APPLICATION_ID, FIREBASE_PROJECT_ID. Values are redacted for security.
```

---

## 6. Giới hạn & Khẳng định Tuân thủ (Constraints & Compliance)

1. **Thiết bị thật & Thông tin định danh thực tế (Credentials):**
   - Lệnh `adb devices -l` cho thấy hiện tại không có thiết bị hoặc máy ảo Android thực tế nào đang gắn kết.
   - Toàn bộ việc xác thực dựa trên các bộ test giả lập tự động, mock server, Room JVM, và Express handlers độc lập.
   - **Xác nhận:** Trạng thái liên kết trực tiếp với Google Play Store, Google Drive OAuth và Firebase Authentication trên môi trường production thực tế được đánh dấu là **`E2E UNVERIFIED`** cho đến khi chủ dự án cấu hình credentials và kiểm thử trên thiết bị vật lý thật.
2. **Không thay đổi phiên bản (Version Bumps):**
   - Giữ nguyên `versionCode` và `versionName` trong `AndroidApp/app/build.gradle.kts`.
3. **Không ghi Secrets lên đĩa:**
   - Không tạo hoặc lưu trữ khóa bảo mật thật, service account private keys, hoặc encryption keys thực tế vào mã nguồn hay đĩa lưu trữ.
4. **Không phát hành / commit:**
   - Không thực hiện bất kỳ lệnh `git commit`, `git push`, `docker deploy`, hay `npm publish` nào.
