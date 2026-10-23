# Vòng 5 — điều phối Codex → Gemini/OpenCode, 11/10/2026

## Baseline đã kiểm tra

18 probes V4 hiện đạt. Gradle compileDebugKotlin, compileReleaseKotlin và testDebugUnitTest đạt (23s). Tuy nhiên 8 probes backend mới C01–C08 và 2 probes snapshot S01–S02 đều thất bại. Source và log baseline trong `docs/audit-vip-2026-10-11-v5/`.

Probes chạy source thật với Firebase/Play/DAO giả, không có network/thiết bị. Các interleaving lỗi là fault injection có kiểm soát. Không đồng nhất với kiểm thử production. Codex sẽ kiểm tra lại sau mỗi lượt OpenCode sửa.

## Việc giao OpenCode — lượt 1

### J01: RTDN phải lấy quyết định từ state Play hiện tại và retry được (C01–C05)

`backend/app.js` renewal/hold catch exception rồi trả OK/ghi COMPLETED: lỗi Play bị mất retry. Renewal lấy expiry mà không kiểm state/product/account binding. PAUSED bị bỏ qua. EXPIRED hint đánh voided dù Play hiện ACTIVE (event đến chậm).

Tạo một đường normalize/validate subscription dùng chung verify và RTDN: product đúng, binding hợp lệ, expiry hữu hạn, string enum state. Không grant ON_HOLD/PAUSED/PENDING; không dùng fallback lineItems[0] hoặc enum số để chiều mock. Handle đầy đủ lifecycle theo latest Play state (renew/recover/restart/grace/cancel/hold/pause/expire/revoke). Phân biệt expired với refund/revoke/account deleted. Không re-enable token ACCOUNT_DELETED. API/DB/missing dependency lỗi phải 5xx và event còn retry; không mark completed khi việc áp dụng thất bại. Event dedup/state update cần idempotent, tránh stale write.

### J02: quyền thiếu expiry và commit dang dở (C06, C08)

GET vẫn cho yearly `isVipActive=true,expiry=null` thành VIP vô hạn. Purchase ghi thành công nhưng recompute entitlement lỗi: verify lần 1 500, lần 2 ALREADY_VERIFIED trả INACTIVE mãi.

Lifetime mới được expiry null hợp lệ. Legacy yearly thiếu expiry phải đối soát hoặc inactive. Retry duplicate phải sửa/reconcile entitlement từ purchase ledger có hiệu lực, không tự khóa vì document thiếu. Không dựa client lặp verify để hoàn thành ack: lưu pending và cung cấp retry/reconciliation server bền vững phù hợp, có test failure/restart. Giữ ownership và aggregate lifetime/yearly đúng.

### J03: deletion phải có tombstone và chặn in-flight verify (C07)

CheckRevoked đầu request chưa đủ. Verify đã authenticate, Play await, DELETE hoàn tất rồi verify token mới vẫn commit và tạo lại entitlement. Đặt deletion marker trước xóa, giữ ngoài root/subcollections bị xóa; check trong purchase commit và recompute/RTDN, dùng transaction/version phù hợp. Retry deletion idempotent; giữ ledger chống replay. Không cho restore/transfer đã in-flight commit sau logout/deletion.

### J04: snapshot phải hash ROM bytes hiện tại (S01–S02)

`GameSnapshotService`: ALL tin contentSha256 cũ; SAVE_ONLY với hash null không resolve ROM nên tạo bundle checksum rỗng mà restore từ chối. Resolve actual ROM theo local path/storage/source được hỗ trợ và hash bytes thật; fail rõ nếu không thể resolve. Cả manifest và file entry phải khớp ROM được đưa vào ZIP. Snapshot source phải ổn định hoặc copy tới staging trước hash/zip; tránh ghi native save đồng thời. Không đưa backup hỏng lên Drive.

## Việc còn lại phải thực hiện — lượt 2 nếu lượt 1 chưa xử lý

### J05: Firebase session và cache VIP (H10 V4 chưa xong)

Auth vẫn SharedPrefs profile, không listener Firebase. Refresh chỉ tại login. Cache boolean không expiry/TTL, cloud gate không refresh. Dùng Firebase làm nguồn real session, giữ DEBUG mock tách; UID/generation thay đổi atomically. Refresh startup/foreground/before cloud; bounded cache expiry/verifiedAt và invalidate trên server false/401/403. Không grant cloud bằng cache vô hạn khi lỗi refresh. Test A→B/A→logout→A, cold start, revoked session, offline/expiry.

### J06: transfer identity, persistence, cancellation và restore recovery (H09/H11 V4 chưa xong)

Drive token vẫn lấy last signed-in account trong khi email metadata cache khác; backup chỉ check app UID. Restore không guard session; lifecycleScope chết là mất job; DB write lỗi bị nuốt rồi toast success. Thêm operation context UID/generation + Drive account thực đã pin; truyền tới network và commit. Check auth/generation trước side effect và commit, không chỉ UID. Job persistent bằng worker/service/state machine nếu cần restart/resume; reconciliation orphan và trạng thái retry/cancel. Không báo hoàn tất khi commit DB fail. Hủy có cleanup NonCancellable hợp lý.

Restore chưa durable journal và bỏ qua boolean rollback rồi xóa staging. Phải preserve recovery artifact khi rollback fail, journal commit/recovery và startup recovery; lock same-game operation/native writes tương ứng. Test fault injection từng bước và restart; no data loss. Không triển khai một worker/journal chỉ có tên mà không có call site/recovery thật.

### J07: callback billing và config release (H12/H13 V4 chưa xong)

Query purchases bỏ responseCode; simulator callback gọi sớm và có thể hai lần; singleton callback chưa scoped session/operation/lifecycle. Fix completion đúng một lần sau verify, typed error/no-purchase/pending/cancel; clear launch failure/account switch/recreate. Pending RESTORE/BACKUP phải resume đúng action sau consent và clear khi hủy.

Firebase config/OAuth/backend metadata vẫn placeholder. Wiring build/config/init và validate theo variant; không hardcode secrets/mock vào release. Nếu cần dữ liệu ngoài dự án thì ghi rõ không thể smoke test thật; vẫn hoàn thiện code/config injection. Không tự đăng ký service/deploy/publish.

## Yêu cầu thực thi và báo cáo

Thực hiện J01–J04 trước, chạy probes V4/V5 và build; sau đó J05–J07. Giữ các sửa đúng cũ. Không sửa/xóa probes audit của Codex, không thêm literal test vào production, không nới assertion. Nếu fixture thiếu API hợp lệ thì báo rõ để Codex chỉnh adapter, không workaround sai trong source.

Chỉ sửa trong `F:\AI\T-Emulator`. Không sửa cấu hình OpenCode toàn cục, không đọc/in credentials, không thao tác git/push/deploy/xóa dữ liệu thật. Có thể dùng dependency cache/JDK để kiểm tra. Không chạy full native build nếu không cần; compile Kotlin debug/release và unit tests phù hợp.

```powershell
node AndroidApp/docs/audit-vip-2026-10-10-v4/backend-regressions.cjs
& ./AndroidApp/docs/audit-vip-2026-10-10-v4/run-restore-audit.ps1
node AndroidApp/docs/audit-vip-2026-10-11-v5/backend-regressions.cjs
& ./AndroidApp/docs/audit-vip-2026-10-11-v5/run-restore-audit.ps1
```

Chạy `npm test` ở backend và Gradle tests. Báo J01–J07 từng mục: source đổi, tests thực chạy, giới hạn/chưa xong. Không chỉ báo 18 test V4 đã xanh. Codex sẽ tự review/test lại và gửi correction tiếp cho cùng session OpenCode.
