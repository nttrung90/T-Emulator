# Bàn giao việc VIP/Cloud còn dở từ Codex sang Antigravity

Người dùng yêu cầu trực tiếp ngày11/10/2026: chuyển việc đang làm dở sang Antigravity làm tiếp. Mục tiêu gốc: rà soát VIP và tính năng liên quan, sửa rồi kiểm tra lại, còn lỗi thì tiếp tục sửa đến khi các lỗi trong phạm vi đã được giải quyết. Bạn nhận cả phần sửa và kiểm thử, không chỉ viết kế hoạch hay xác nhận đã đọc.

## Workspace và trạng thái

- Workspace chính xác: `F:/AI/T-Emulator`; Android/Gradle root: `F:/AI/T-Emulator/AndroidApp`; backend: `AndroidApp/backend`.
- Tiếp tục source hiện tại, giữ các thay đổi đã có. Cwd không phải Git repo theo kiểm tra đầu lượt; không reset/revert/create checkout tùy tiện.
- Codex đã giao OpenCode/Gemini Round1/2/3/3B và kiểm tra độc lập sau từng lượt. Round3C bị ngắt bởi quota sau khi sửa VipMetadataDecoder timestamp. Pro rồi Flash trong OpenCode đều báo hết quota; CLI exit0 với thông báo quota không có nghĩa công việc hoàn thành. OpenCode không còn job đang sửa, nên Antigravity có thể tiếp quản cùng workspace.
- Đọc phần cập nhật mới nhất ở CUỐI `VIP_AUDIT_STATUS_2026-10-11.md`; phần đầu là lịch sử. Đọc source thực tế, đừng tin báo cáo model đã hoàn tất nếu callsite chưa sửa.

## Baseline đã được Codex xác minh

Backend npm53/53 PASS; actual Express audit C01-C21 21/21 PASS. Production Kotlin policy/session/metadata20/20 PASS sau sửa timestamp. CompileDebugKotlin và CompileReleaseKotlin PASS. Android unit55/57 PASS (2 actual repository assertions FAIL). Snapshot/restore/rollback4/6 PASS (S05,S06 FAIL). New actual acknowledgement C22 FAIL ngoài bộ53backendtests.

Các log/probes trong `F:/AI/T-Emulator/AndroidApp/docs/audit-vip-2026-10-11-v5/`: `npm-verified-round3c.txt`, `policy-after-round3c.txt`, `android-after-round3c-partial.txt`, `restore-initial-round4.txt`, `ack-initial-round5.txt`, source Kotlin/CJS, logsOpenCode. Không cần đọc/export auth/session credentials của OpenCode.

## Năm lỗi đã tái hiện — ưu tiên sửa

1. EntitlementRepository.ensureFreshEntitlement còn fallback cache lần hai sau refresh=false. Backend config thiếu vẫn được cachegrant. Actual test `missingBackendConfigurationMustNotBecomeAValidCachedCloudGrant` FAIL. Chỉ typed transport fallback trong refresh được phép quyết định; config/auth/schema denial phải giữ denial.
2. EntitlementRepository.saveEntitlementMetadata luôn ghi global StateFlow theo UID truyền vào; metadataB có thể grant flowA. Actual test `writingOtherUsersMetadataMustNotGrantCurrentUser` FAIL. Test revoke UID khác giữ grant hiện tại đã PASS nhưng timer cũng phải giữ đúng. Cache/flow/timer commits phải atomic cùng AuthSessionTracker.commitIfCurrent; không có global latewrite sau guard.
3. GameRestoreService lấy targetBasename từ archive ngoài outer.zip, trong khi native VirtualFile.GetFileName lấy folder/inner.nes. BatteryManager/SaveStateManager dùng innerbasename. S05 actualsnapshot→restore mất inner.sav/inner_1.mss. Giữ hỗ trợ ZIP/7z ALL/SAVE_ONLY, không tắt feature hay chọn tùy tiện firstentry.
4. GameStorageManager.restoreFromRollbackBackup xóa live game trước kiểm tra source và báo true nếu backup không tồn tại. S06 FAIL. Reject absent/non-directory backup trước destructive target operation, giữ live save; empty existing backup cho originally newgame là trường hợp riêng.
5. backend acknowledgePlayPurchase trả undefined nếu SDK API thiếu; verify vẫn ghi acknowledgementState=1. C22 FAIL. Giữ verified entitlement nhưng ack phải pending/retry; subscription thiếuAPI không fallback products endpoint.

## Trình tự tiếp tục — phải thực hiện cả ba phần

1. Đọc và hoàn thành `OPENCODE_VIP_V5_ROUND3C.md`. VipMetadataDecoder đã dùng now và20policyprobes PASS; phần ensureFresh và atomic repository state còn dở. `app/src/test/java/com/temulator/app/billing/EntitlementRepositoryIntegrationAuditTest.kt` có3 tests gọi actual repositories, dùng coroutines-test dispatcher và preferencefixture; giữ nguyên assertions. Sửa source để cả3 PASS, kiểm tra consumers thật.
2. Đọc và hoàn thành `OPENCODE_VIP_V5_ROUND4.md`: app UID/generation + actualDriveidentity/generation pin xuyên suốt token/folder/upload/chunks/list/download/restorecommit; durable transferjournal/orphanreconcile + actual recovery callsite; Room atomic backup+terminalstate; cancellationcleanup; OAuth pending BACKUP/RESTORE; MoreFragment pin account deletion ngay lúc mởdialog và guard token/HTTP/latecleanup; restorejournal/crashrecovery trước/sauDAO + giữ rollback khi rollbackfails; sharedcoordinator nối nativeplay/snapshot/restore actualcallsites; S05/S06. Hai probes mới bổ sung ở CUỐI prompt, không bỏ qua.
3. Đọc và hoàn thành `OPENCODE_VIP_V5_ROUND5.md`: Billing pendingaction exactonce/session/lifecycle/launchfail/cancel/destroy, typedqueryerror vsNotOwned và completion connectionfailure; FirebaseOptions/Applicationbootstrap/publicbuildconfig/WebOAuth/backendHTTPS/releasevalidation/runtimefailclosed/purchasepreflight; durable encryptedackjob + protectedOIDCscheduler/lease/backoff/requeryownership/tombstone/restarttests/C22. Purchasepreflight/Authlogoutrace/C22 bổ sung ở CUỐI prompt, không bỏ qua.

Tên prompt có OpenCode chỉ là lịch sử, đây là yêu cầu dành cho Antigravity tiếp tục. Không cần điều khiển OpenCode hay chờ quota plugin. Các mục cloud/Billing/config/durableack vẫn cần source work thật, không được dừng sau5assertionsxanh rồi báo tất cả hoàn thành.

## Kiểm thử bắt buộc

PowerShell từ AndroidApp dùng JBR17 đã có, offline cache:

```powershell
& 'C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe' -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:compileDebugKotlin :app:compileReleaseKotlin :app:testDebugUnitTest --offline --no-daemon --console=plain
npm test --prefix backend
node docs/audit-vip-2026-10-11-v5/backend-regressions.cjs
node docs/audit-vip-2026-10-11-v5/backend-ack-audit.cjs
powershell -NoProfile -ExecutionPolicy Bypass -File docs/audit-vip-2026-10-11-v5/run-policy-audit.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File docs/audit-vip-2026-10-11-v5/run-restore-audit.ps1
```

Standalone runners compile actual production files bằng Kotlin1.9 và cached deps. Nếu thêm imports/helpers mới, cập nhật danh sách production sources/dependencies trong runner phù hợp, KHÔNG sửa/weaken assertions hoặc thay implementation production bằng mocklogic. Giữ old V2/V3/V4 backend/restore regressions ở các auditdirs ngày09/10 và GameRestoreSecurityTest. Unit tests phải kiểm tra actual production helpers và consumers; không chép reducer logic vào test để báo PASS.

Thêm fault-injection tests có ý nghĩa cho các phần mới: appABA/Drivechange, stalecallbacks, queryerror+empty, restore rollbackfail/crashbefore/afterDB, nativebusycoordinator, durable remoteorphan recovery/DBfailure/cancellation, ackrestart/lease/deleted/unauthorized/encryption. Run tests sau mỗi nhóm chỉnh; nếu FAIL, đọc nguyên nhân và sửa lại rồi kiểm tra lặp.

## Giới hạn và báo cáo

Không đổi version/buildnumber, không commit/push/deploy/publish, không nhúng secrets vàoAPK hoặc tạo keys/account mới. Không thay đổi dự án khác. Không xóa audit artifacts hay save người dùng. Credentials/serviceaccount/private encryptionkey phải env server, publicFirebaseconfig cóvalidation không bịa. Release distribution task thiếu config phải failclear, local compile/test unconfigured vẫn được.

ADB kiểm tra trước chưa có device/emulator; GooglePlay/Drive/Firebase/OAuthproduction E2E với credentials thật chưa xác minh. Không coi fake external service tests là E2E. Sourcewiring/configvalidation vẫn cần làm đầy đủ; thiếu credentials thật ghi UNVERIFIED rõ ràng.

Ghi báo cáo tiến độ/kết quả vào `AndroidApp/docs/ANTIGRAVITY_VIP_LOOP_REPORT_2026-10-11.md`, cập nhật `VIP_AUDIT_STATUS_2026-10-11.md` khi có bằng chứng mới. Liệt kê files thật đã sửa, sốtest/exitcode/log, việc đã/chưa xong và blocker thực. Không khẳng định tuyệt đối không còn lỗi từ một lần compile hay lời model. Khi hoàn tất source/test trong scope, báo rõ để user/Codex có thể kiểm tra độc lập tiếp. Nếu bản thân Antigravity cũng bị quota, nói đúng blocker và lưu checkpoint thay vì giả báo đang chạy.

Bắt đầu ngay bằng đọc status phần cuối, Round3C và actual EntitlementRepository source/tests; sửa/verify rồi đi tiếp Round4 vàRound5, tự kiểm tra và sửa lặp đến khi không còn lỗi tái hiện trong phạm vi này.
