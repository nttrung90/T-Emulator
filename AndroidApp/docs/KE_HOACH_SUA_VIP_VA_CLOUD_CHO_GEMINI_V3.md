# Kế hoạch sửa VIP và Cloud — vòng 3, 10/10/2026

## Phạm vi và kết luận

Đã kiểm tra mã sau vòng sửa V2. Chưa thể kết luận sẵn sàng phát hành: 8 ca hồi quy mới đều thất bại, ngoài ra có 2 tham chiếu không resolve trong UI backup. Tài liệu này là yêu cầu sửa, không phải xác nhận các lỗi đã được sửa.

Kết quả kiểm tra tại thời điểm audit:

- `npm test` trong `AndroidApp/backend`: 16/16 đạt.
- Bộ audit backend vòng trước: 5/5 đạt; bộ restore vòng trước: 5/5 đạt. Đây là các ca cũ, có phần trùng với test backend.
- `run-all-android-tests.ps1` vòng trước: 12 restore + 6 VIP đạt. Script chỉ chạy các nhóm này, dùng stub `BuildConfig.DEBUG=true`; không chứng minh toàn bộ app hoặc release đạt.
- Bộ mới `docs/audit-vip-2026-10-10`: backend 4/4 thất bại; restore 4/4 thất bại. Assertion mô tả hành vi đúng, nên hiện tại exit code 1 là kết quả tái hiện lỗi.
- Gradle `:app:compileDebugKotlin :app:compileReleaseKotlin --offline --no-daemon --console=plain` đã trả kết quả: cả hai task thất bại vì `Unresolved reference: authRepo` (dòng 162) và `Unresolved reference: Log` (dòng 191) trong CloudBackupDialogHelper. Log trích tại `audit-vip-2026-10-10/compile-results.txt`. Kiểm tra compiler riêng bị nhiễu classpath cache đã loại bỏ; kết luận này dựa trên Gradle thật.

Backend probes chạy handler Express thật với Firebase/Play giả lập; restore probes chạy source restore/storage thật với DAO giả, không có thiết bị Android hay Room thật. Chưa chạy giao dịch Play, Pub/Sub hoặc Drive thật.

## Sửa theo thứ tự

### G01 — P1: sửa lỗi biên dịch UI backup trước

`app/src/main/java/com/temulator/app/cloud/CloudBackupDialogHelper.kt`: `executeBackup` dùng `authRepo` tại dòng 162 nhưng không nhận tham số này và object không có thuộc tính tương ứng; dòng 191 dùng `Log.e` nhưng thiếu import `android.util.Log`.

Truyền dependency đúng qua lời gọi và thêm import. Kiểm tra UID/generation trước upload, trước từng bước commit và sau tác vụ bất đồng bộ; chỉ kiểm tra sau upload không ngăn upload bằng tài khoản đã đổi. Chạy compile cả debug lẫn release sau khi sửa, không chỉ test source tách rời.

### G02 — P0: tách xác thực RTDN khỏi Firebase user token (N01)

`backend/app.js:106–112` fallback từ OIDC về `admin.auth().verifyIdToken`. `backend/server.js` không inject `verifyOidcToken`, vì vậy đường production vẫn dùng Firebase token. N01 cho thấy token người dùng được middleware chấp nhận có thể gửi voided event cho purchase token đã biết: HTTP 200 và VIP bị tắt.

Wiring verifier Google OIDC thực sự tại server entrypoint; kiểm tra chữ ký, issuer, audience, email service account được cho phép và trạng thái email verified. Không fallback sang Firebase user auth. Cấu hình verifier thiếu phải từ chối request. Nếu giữ secret header, cấu hình rõ và kiểm tra riêng; không chấp nhận secret trên query production.

Nghiệm thu: user Firebase token, sai audience/email/issuer, token hết hạn đều không mutate DB; Pub/Sub OIDC hợp lệ xử lý được qua wiring production. Mock phải phân biệt hai loại token.

### G03 — P1: đọc đúng hợp đồng subscriptionsv2 (N02)

`backend/app.js:308–310` so sánh `subscriptionState` với số 2/4/6. Play subscriptionsv2 trả tên enum dạng chuỗi. N02 trả `SUBSCRIPTION_STATE_ACTIVE` với expiry tương lai nhưng server trả 400.

Map enum theo hợp đồng API, kiểm tra line item đúng product được cho phép, expiry hợp lệ và account binding. Không lấy mặc định `lineItems[0]` mà bỏ qua product. Xử lý rõ ACTIVE, IN_GRACE_PERIOD, CANCELED còn hạn, ON_HOLD, PAUSED, EXPIRED, PENDING theo chính sách quyền; không cấp quyền vì giá trị số hoặc enum lạ. Dùng API acknowledge đúng loại sản phẩm; không fallback subscription thành in-app product.

Tham chiếu chính thức: [SubscriptionPurchaseV2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2), [xác thực Pub/Sub push](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions).

### G04 — P1: expiry và nhánh ALREADY_VERIFIED không được cấp quyền vô điều kiện (N03, N04)

GET entitlement hiện chỉ dựa `isVipActive`. Expiry tính được từ Play chưa được lưu đầy đủ vào purchase/entitlement. N03 seed entitlement yearly đã hết hạn vẫn nhận `isVip=true`. N04 seed purchase yearly hết hạn, không còn entitlement, verify vẫn trả `entitled=true, ALREADY_VERIFIED`. N04 chứng minh phản hồi cấp quyền sai; không khẳng định handler đã tạo lại document entitlement.

Lưu trạng thái, expiry, product, token/account binding và thời điểm đối soát. Phân biệt lifetime/yearly. GET không cấp yearly đã hết hạn; quyết định cho dữ liệu cũ thiếu expiry phải rõ ràng, có migration/đối soát. Nhánh duplicate phải lấy quyết định quyền hiện hành, không đồng nhất “đã ghi nhận purchase” với “VIP đang hiệu lực”.

RTDN subscription hiện chỉ xử lý type 12/13 bằng voided. Cần requery Play cho lifecycle liên quan, xử lý renew/recover/hold/pause/cancel/expire và sự kiện đến sai thứ tự. Expired không phải cờ voided vĩnh viễn khiến recovery bị khóa. Purchase, entitlement và event dedup phải nhất quán; lỗi giữa các write phải có retry/reconciliation. Acknowledge lỗi cần retry bền vững, không chỉ chờ client gọi verify lại.

### G05 — P1: bắt buộc manifest integrity (N05)

`GameRestoreService.kt:132–133` chỉ kiểm tra danh sách khi `files` có mặt. N05 bỏ `files[]` và checksum ROM, bundle ALL vẫn commit.

Bắt buộc schema, danh sách file và checksum/size hợp lệ theo loại backup. So sánh đầy đủ file giải nén với manifest trước khi sửa dữ liệu đích. Chuẩn hóa đường dẫn và từ chối duplicate/collision sau chuẩn hóa, kể cả duplicate ZIP entries; kiểm tra checksum ROM độc lập. Nếu hỗ trợ legacy phải có quy tắc/version riêng, không bỏ validation ngầm.

Nghiệm thu: thiếu/sai kiểu files, checksum, size; file thừa; duplicate entry; traversal đều fail trước mutation.

### G06 — P1: đổi basename save state phải giữ số slot (N06)

`GameRestoreService.kt:290–298` map mọi file thành `targetBasename.extension`. Hai file `remote_1.mss`, `remote_2.mss` cùng bị map vào `local.mss`; thiếu `local_1.mss` và `local_2.mss`. Core dùng tên `<basename>_<slot>.mss` (`Core/Shared/SaveStateManager.cpp`).

Map theo naming contract thực của core, chỉ thay prefix ROM và giữ slot/suffix. Từ chối tên mơ hồ/collision thay vì overwrite. Áp dụng tương tự các biến thể save thực sự được hỗ trợ; không nhân bản bừa mọi file sang cùng basename.

Nghiệm thu: ít nhất 2 slot, kiểm tra tên và nội dung riêng biệt; cùng basename và khác basename; mở lại game trên thiết bị và load từng slot.

### G07 — P1: không ghi đè game khác khi remote ID trùng (N07, N08)

`GameRestoreService.kt:207–248`: ALL có manifest ID trùng nhưng ROM hash khác thì `existingGame=null`, sau đó vẫn dùng manifest ID làm local ID. N07 chứng minh ROM local bị thay bằng ROM khác. Lookup theo checksum cũng không kiểm tra console: N08 SAVE_ONLY khác console vẫn thành công.

Quyết định identity trước commit: checksum + console + ROM local hợp lệ. Với ALL ID collision khác nội dung, tạo local ID mới an toàn hoặc trả conflict rõ ràng; không overwrite thư mục/record đang có. SAVE_ONLY phải xác nhận ROM tồn tại và đúng nội dung/console, không tin metadata checksum cũ. Test giữ nguyên byte ROM, save và record cũ khi conflict/failure.

### G08 — P1: hoàn thiện tính an toàn restore khi lỗi hoặc process chết

Đây là phát hiện đọc mã, ngoài 8 probes: DAO có thể null khi khởi tạo DB thất bại, `gameDao?.insertOrUpdate` vẫn đi tới success. Rollback chưa có journal bền vững; kết quả restore rollback không được xác nhận, trong khi cleanup staging có thể xóa bản phục hồi cuối cùng.

Fail trước mutation nếu không có DB. Journal/commit protocol và recovery sau restart phải bao phủ filesystem + Room; giữ bản rollback nếu phục hồi lỗi. Khóa restore/backup/play cho cùng game để tránh snapshot hoặc ghi chồng dữ liệu đang thay đổi. Nghiệm thu bằng fault injection ở từng bước copy/DB/rollback và process restart; không báo thành công khi chỉ copy file được.

### G09 — P1: đồng bộ session và VIP khi cold start

`EntitlementRepository` có guard generation nhưng `currentActiveUid` khởi tạo null. Các call site `onUserChanged/refreshEntitlement` hiện chỉ ở login/logout của MoreFragment. Session lưu sẵn khi khởi động chưa đi qua wiring này, nên kết quả verify có thể bị loại dù người dùng vẫn đăng nhập.

Dùng nguồn session Firebase thống nhất và auth-state listener; khởi tạo UID/generation trước request, refresh khi startup/foreground và trước thao tác cloud theo chính sách cache có hạn. Không giữ VIP true vô thời hạn khi refresh thất bại. Test cold start đã login, đổi A sang B lúc request chậm, logout, token hết hạn, offline và refund khi app đang mở.

### G10 — P1: transfer và danh tính Drive phải nhất quán

Đọc mã: backup vẫn chạy trong lifecycleScope; có TransferRecord không đồng nghĩa job có thể recover. Token Drive lấy từ tài khoản Google cuối cùng, chưa ràng buộc xuyên suốt job. Check app UID sau upload là quá muộn.

Job phải có định danh app UID + Drive account + generation ổn định, kiểm tra trước side effect và commit; hủy an toàn khi đổi tài khoản. Dùng cơ chế công việc bền vững phù hợp nếu yêu cầu tiếp tục sau đóng màn hình/process chết; lưu tiến độ/trạng thái có thể phục hồi và reconciliation remote orphan. Không nuốt lỗi ghi DB rồi báo hoàn tất. Nghiệm thu rotate/background/restart/logout/account switch giữa upload.

### G11 — P2: callback billing và pending action cần đủ lifecycle

Đọc mã: callback mua lưu trong singleton chưa gắn account/lifecycle; queryPurchases chưa phân biệt response code lỗi với danh sách rỗng. pendingBackupGame mới giúp consent tiếp tục backup nhưng cần clear khi hủy paywall/dialog; restore cũng cần pending action riêng.

Ràng buộc callback một lần đúng operation/session, clear khi cancel/failure/destroy, xử lý query lỗi rõ ràng. Test mua thành công sau recreate, hủy mua, already-owned, query lỗi và hủy backup rồi mở restore; không chạy lại game/action cũ.

### G12 — P1: kiểm tra deletion và cấu hình release thật

Đọc mã: `verifyIdToken(token)` không yêu cầu kiểm tra revoked; delete account và verify purchase có thể chạy đua. Record purchase giữ lại không được trở thành đường cấp quyền cho tài khoản đã xóa. Thiết kế deletion state/tombstone hoặc kiểm tra trạng thái user phù hợp, chặn commit/transfer sau deletion, cleanup local entitlement/jobs theo UID. Kiểm thử lỗi từng bước và idempotent retry; không yêu cầu xóa purchase ledger dùng chống replay.

Xác nhận Firebase app initialization/config, OAuth client, backend URL và release behavior bằng build thật. Không dùng stub DEBUG hoặc tài khoản mock để kết luận tích hợp production hoạt động. Nếu thiếu cấu hình bên ngoài, ghi rõ biến/cấu hình cần cấp và bước kiểm thử đang bị chặn; không hardcode secret hay báo đã xác minh production.

## Trình tự giao việc và nghiệm thu

1. Sửa G01, chạy compile debug/release; sửa G02 trước triển khai backend.
2. Sửa G03–G04, thêm test contract đúng JSON Google và state lifecycle.
3. Sửa G05–G08, chạy probes restore và test lỗi commit/recovery.
4. Hoàn thiện G09–G12, kiểm thử tích hợp session, billing, Drive và release.
5. Giữ các test cũ; đưa 8 ca mới vào suite thường xuyên. Không sửa assertion hoặc giả lập sai API để tạo kết quả xanh. Bổ sung test thực sự kiểm tra DB/concurrency và Android lifecycle, vì fake Map/DAO không chứng minh transaction/Room hoạt động.

Lệnh từ thư mục gốc `F:\AI\T-Emulator`:

```powershell
node AndroidApp/docs/audit-vip-2026-10-10/backend-regressions.cjs
& ./AndroidApp/docs/audit-vip-2026-10-10/run-restore-audit.ps1
```

Chạy thêm `npm test` tại backend, các regression vòng trước và Gradle debug/release/unit tests liên quan. Script restore đang dùng đường dẫn cache/JDK máy audit, cần điều chỉnh khi chạy trên máy khác. Không coi test tách source là thay thế build app.

## Prompt gửi Gemini

```text
Hãy sửa tiếp luồng VIP và Cloud theo AndroidApp/docs/KE_HOACH_SUA_VIP_VA_CLOUD_CHO_GEMINI_V3.md.
Đọc toàn bộ kế hoạch và probes trong AndroidApp/docs/audit-vip-2026-10-10 trước khi sửa. Hiện 8 probes mới thất bại; các test cũ đạt nhưng chưa bao phủ lỗi mới. Sửa G01 compile trước, G02 xác thực RTDN, rồi subscription/expiry, restore integrity/identity/slot, session/transfer/deletion và release wiring.
Giữ các sửa đúng vòng trước. Không xóa hoặc nới assertion để vượt test, không dùng số enum subscription thay cho JSON Google thật, không dùng DEBUG stub để khẳng định release hoạt động. Với hành vi chưa rõ, xác minh source/API và bổ sung test trước khi đổi logic. Không triển khai production trong nhiệm vụ này.
Sau khi sửa, chạy test cũ + 8 probes mới, build debug/release và kiểm thử phù hợp với từng thay đổi. Báo bảng G01–G12: đã sửa/chưa xong, file thay đổi, bằng chứng kiểm thử, những phần thiếu cấu hình hoặc chưa kiểm thử thật. Không tuyên bố hoàn tất nếu còn mục chưa xử lý.
```
