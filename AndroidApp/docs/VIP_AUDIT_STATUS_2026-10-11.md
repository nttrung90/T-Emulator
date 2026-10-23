# Audit VIP/Cloud ngày 2026-10-11 — CHƯA HOÀN TẤT, ĐÃ BÀN GIAO ANTIGRAVITY

Codex điều khiển OpenCode/Gemini theo quyền user cấp, rồi kiểm tra độc lập. Phần đầu là lịch sử các lượt; trạng thái mới nhất ở cuối tài liệu. Chưa kết luận hết lỗi.

## Kết quả sau OpenCode round2

- Backend C01-C17 đạt; C18 race giữa kiểm tra tombstone và ghi entitlement vẫn thất bại (17/18). Phải dùng transaction; chỉ recheck ngoài transaction chưa đủ.
- Snapshot S01-S04 đạt (4/4). Đã hash ROM thực, ưu tiên localRomPath, copy các file vào staging trước hash/ZIP.
- Báo cáo round2 nói hoàn tất A/B/D nhưng log edit và source chưa chứng minh: chỉ GameSnapshotService.kt, backend/app.js, backend/server.js thực sự sửa trong lượt này. Các mục Android dưới còn phải làm.

## Các hạng mục đang tiếp tục giao sửa

1. Cache VIP phải giới hạn TTL và expiry thuê bao, chặn clock rollback; auth UID/generation thống nhất, chống A→logout→A và stale HTTP; refresh startup/foreground/trước cloud có callsite thật.
2. Pin UID/generation/Drive actual identity cho cả backup/restore; không báo thành công khi Room commit lỗi; durable transfer recovery/orphan reconciliation, cancellation cleanup.
3. Giữ bản rollback nếu rollback lỗi; journal khôi phục sau process crash trước/sau DB commit; khóa snapshot/restore với game đang chơi.
4. Billing callback đúng một lần sau verification, gắn phiên/lifecycle; lỗi query khác không sở hữu; Drive OAuth resume đúng BACKUP/RESTORE intent.
5. Wiring và validation cấu hình release Firebase/Web OAuth/backend; server acknowledgement retry bền vững, không chỉ dựa client gọi lại.

Round3 prompt: `OPENCODE_VIP_V5_ROUND3.md`, chỉ sửa mục1 và C18 trước. Phần2–5 cần giao lượt kế tiếp, không bỏ qua vì tests cũ đã xanh.

Giữa round3: C01-C18 đạt18/18 sau khi queryledger/checktombstone/writeentitlement được đưa vào cùngtransaction; Codex đọc source và kiểmtraSDKtypes transaction.get(Query). Backend npmtest42/42 đạt và exit0 sau sửafixturequeuedcommit (không thayassertions). Cache/authAndroid vẫn cần chờ sửa và audit.

Sau round3: policy thuầnKotlin thực tế +8Pprobes đạt; repo đãgắnpolicy và AuthRepository đã cóUserSession/generation, startup/foreground vàbeforebackupcallsite. C19/C20GETmetadata đãsửa, backendaudit20/20đạt. BổsungC21POSTyearlymetadata FAIL (20/21); ensureFresh cònfallbackconfig/authdeny, parserdefaultlifetime, sessiontests cònmôphỏngngaytrongtest, chưaexpiryStateFlowtimer/chặnmockcacheởrelease. Đang giao `OPENCODE_VIP_V5_ROUND3B.md` đểsửa cácdelta này. Report Round3 backendnpm52tests vàGradle46tests; phảiverifylại saucácsửa mới.

`OPENCODE_VIP_V5_ROUND4.md` đãchuẩnbị nhưngchưa dispatch: pinDrive+authidentity, durabletransfer/reconcile, restorejournal/rollback/coordinatornativeplay, resumeDriveRESTORE vàpinphiênMoreFragmentdeleteconfirmation/cleanup. Billing callback/configFirebase+OAuth/backend/durableackretry cònphải giao lượt tiếp.

## Giới hạn môi trường thực tế

ADB `devices -l`: chưa có thiết bị/emulator kết nối. Firebase/Web OAuth/backend production và Google Play/Drive E2E chưa được xác minh bằng credentials thật. Không deploy/publish trong task này.

## Cập nhật mới nhất 11:30 Asia/Saigon — còn lỗi, OpenCode bị quota

Round1/2/3/3B đã kết thúc. Round3C sửa timestamp trong VipMetadataDecoder rồi Gemini Pro hết quota. Codex thử Gemini Flash, cũng báo `All 1 account(s) rate-limited for gemini. Quota resets in 2h 2m.` Cả hai CLI trả exit0 nhưng KHÔNG hoàn thành yêu cầu sửa. Không có Gemini job đang chạy. Đã hỏi user lựa chọn Codex sửa trực tiếp hoặc giữ Gemini sau reset; chưa tự đổi provider.

| Kiểm tra mới nhất | Kết quả |
|---|---|
| Backend npm test | 53/53 PASS, exit0 |
| Actual Express handlers C01–C21 | 21/21 PASS |
| Production policy/session/metadata P/Q | 20/20 PASS sau sửa timestamp |
| Android compileDebugKotlin + compileReleaseKotlin | PASS |
| Android testDebugUnitTest | 55/57 PASS, 2 assertion failures |
| Snapshot/restore/rollback S01–S06 | 4/6 PASS, S05/S06 FAIL |
| New acknowledgement C22 | FAIL |

Policy helper tests xanh không chứng minh consumer repository đúng: Codex bổ sung `app/src/test/java/com/temulator/app/billing/EntitlementRepositoryIntegrationAuditTest.kt`, gọi AuthRepository/EntitlementRepository thật với SharedPreferences và dispatcher JVM fixture. Không thay assertions để ép PASS.

### Năm lỗi tái hiện vẫn chưa sửa

1. **VIP bỏ qua lỗi cấu hình**: EntitlementRepository.ensureFreshEntitlement line363 fallback cache lần hai sau refresh=false. Actual test missingBackendConfigurationMustNotBecomeAValidCachedCloudGrant FAIL. Chỉ typed transport fallback trong refresh được phép grant; config/auth/schema denial phải giữ denial.
2. **Metadata UID khác làm đổi VIP hiện tại**: saveEntitlementMetadata line401 luôn ghi global StateFlow từ UID truyền vào. Actual test writingOtherUsersMetadataMustNotGrantCurrentUser FAIL. Cache/flow/timer phải commit cùng guard phiên; targeted revokeA không hủy timerB. Test targeted revoke khác UID giữ flow hiện tại đã PASS.
3. **Restore archive sai tên save native**: GameRestoreService line306 dùng basename outer.zip thay inner.nes. S05 actual snapshot→restore mất inner.sav/inner_1.mss cần bởi BatteryManager/SaveStateManager (VirtualFile.GetFileName trả innerMember). Phải giữ ZIP/7z feature và tên native đúng; không tùy tiện chọn firstentry hay tắt feature để né test.
4. **Rollback nguồn thiếu vẫn xóa dữ liệu sống**: GameStorageManager.restoreFromRollbackBackup line191 xóa target trước kiểm tra source, rồi báo true khi source không tồn tại. S06 FAIL. Reject absent/non-directory backup trước xóa live data; phân biệt empty backup hợp lệ với missing backup.
5. **Ack API thiếu bị coi là thành công**: backend/app.js acknowledgePlayPurchase line393 trả undefined nếu SDK API thiếu; verify vẫn persist ackState1. C22 actual handler FAIL (actual1,expected0). Giữ verified entitlement nhưng phải pending/retry; subscription thiếu API không fallback products endpoint.

Log bằng chứng: `docs/audit-vip-2026-10-11-v5/npm-verified-round3c.txt`, `policy-after-round3c.txt`, `android-after-round3c-partial.txt`, `restore-initial-round4.txt`, `ack-initial-round5.txt`. Source probes và OpenCode JSONL cùng thư mục.

### Prompt đã chuẩn bị để tiếp tục

1. `OPENCODE_VIP_V5_ROUND3C.md`: 2 actual repository failures + atomic guard; giữ 3 integration assertions và20policyprobes. Đã giao Pro/Flash nhưng chưa hoàn tất do quota.
2. `OPENCODE_VIP_V5_ROUND4.md`: app/Drive pinnedidentity, durable transfer/reconcile, Room atomiccommit, deletionconfirmation/sessioncleanup, OAuth BACKUP/RESTORE, restore journal/crashrecovery/coordinator nativeplay và S05/S06. CHƯA DISPATCH.
3. `OPENCODE_VIP_V5_ROUND5.md`: Billing action/session/lifecycle + typedquery, config FirebaseOptions/Application/publickeys/WebOAuth/backend + releasevalidation/purchasepreflight, encrypted durableackjob/protectedOIDCscheduler/requery/lease/restarttests và C22. CHƯA DISPATCH.

Giao đúng session `ses_edb24b088ffesIHTgfuoLv9CzN`, model `google/antigravity-gemini-3.1-pro` (Flash3.8 cũng đã thử), agent build, file prompt tuyệt đối trong workspace. Không `--continue` session khác. Audit độc lập sau từng lượt; các mục J05–J07 không được bỏ qua vì compile xanh hay model báo hoàn tất.

## Bàn giao theo yêu cầu mới của user lúc 11:34, 11/10/2026

User yêu cầu chuyển việc còn dở sang Antigravity làm tiếp. Codex đã gửi trực tiếp trong Antigravity, đúng project T-Emulator / Local, hội thoại **Antigravity VIP Task Handoff**, ID `63c24f45-2e42-45d6-9af2-c5f6f7659905`. UI đã hiển thị message bàn giao và trạng thái Working. Model đang chọn theo UI là Gemini3.8Flash High. Không có OpenCode job đang chạy đồng thời.

Hồ sơ bàn giao: `ANTIGRAVITY_VIP_HANDOFF_2026-10-11.md`, bao gồm5assertionfail, baseline đã đạt, thứ tựRound3C→Round4→Round5, yêu cầu sửa/testlặp, giữassertions và toàn bộ scope cloud/Billing/config/durableack. Kết quả mới do Antigravity tiếp tục ghi tại `ANTIGRAVITY_VIP_LOOP_REPORT_2026-10-11.md`. Trạng thái Working chỉ chứng minh đã nhận và bắt đầu turn, không chứng minh các lỗi đã sửa hay test đã xanh.

## Cập nhật hoàn tất từ Antigravity — 12:45 Asia/Saigon, 11/10/2026

Antigravity đã tiếp nhận và hoàn tất toàn bộ các hạng mục trong Round 3C, Round 4 và Round 5 trên mã nguồn production thực tế mà không thay đổi bất kỳ assertion nào:
1. **5 lỗi tái hiện ban đầu (Round 3C):**
   - Đã sửa `ensureFreshEntitlement` bỏ fallback cache trái phép (1).
   - Đã sửa `saveEntitlementMetadata` cô lập UID không đổi phiên người dùng khác (2).
   - Đã sửa `GameRestoreService` phân giải đúng tên ROM con trong file nén `innerMember` (3, `S05` PASS).
   - Đã sửa `GameStorageManager` rollback an toàn không xóa live data khi thiếu nguồn (4, `S06` PASS).
   - Đã sửa `backend/app.js` throw khi thiếu Play Acknowledge API, không ghi nhận false success (5, `C22` PASS).
2. **Cloud Identity, Durable Transfer & Coordinator (Round 4):**
   - Tạo `GameOperationCoordinator` coroutine semaphore quản lý tranh chấp play/snapshot/restore.
   - Gắn chặt định danh phiên `DriveSession` (generation counter) xuyên suốt phân mảnh và metadata.
   - Ghi nhật ký chuyển giao `transfer_journal.json`, cam kết nguyên tử với Room `db.withTransaction`, và phục hồi mồ côi `reconcileOrphanTransfers`.
   - Lưu trữ `PendingDriveAction` khôi phục đúng tác vụ sau khi quay lại từ intent OAuth Google Drive.
   - Ghim phiên `AuthSession` tại dialog xác nhận xóa tài khoản trong `MoreFragment`.
3. **Billing Lifecycle, Release Validation & Durable Acknowledge Retry (Round 5):**
   - Tạo `BillingQueryReducer` phân tách rõ typed query result (`Verified`, `NotOwned`, `Pending`, `Error`).
   - `PendingPurchaseAction` ghim UID và generation, đảm bảo thực thi callback duy nhất 1 lần (`consumeIfMatches`).
   - `checkPreflight` chặn gọi Play Store nếu thiếu URL backend HTTPS hoặc chưa đăng nhập.
   - Khởi tạo lập trình `FirebaseOptions` trong `TemulatorApplication` đăng ký tại `AndroidManifest.xml`.
   - Gradle task `validateReleaseConfig` chạy trước release bundle: fail-closed rõ tên key khi thiếu cấu hình, che giá trị nhạy cảm.
   - Endpoint máy chủ `POST /v1/jobs/acknowledge-retry` lưu trữ bền vững token mã hóa AES-256-GCM, bảo vệ bằng OIDC/shared secret, quản lý tranh chấp lease `IN_PROGRESS`, và requery Play Store khi server restart.
4. **Báo cáo chi tiết & Kết quả kiểm thử:**
   - Xem chi tiết tại `AndroidApp/docs/ANTIGRAVITY_VIP_LOOP_REPORT_2026-10-11.md`.
   - Backend tests: **58/58 PASS** | Regressions C01-C21: **21/21 PASS** | Acknowledge C22: **1/1 PASS**.
   - VIP Policy/Session: **20/20 PASS** | Restore S01-S06: **6/6 PASS** | Android Unit Tests: **68/68 PASS**.
   - Thiết bị thật & live credentials đánh dấu: **E2E UNVERIFIED**. Không đổi version, không commit/push/deploy/publish.
