# Kiểm định Google Play — T-Emulator 1.1.0 (13)

Ngày 13/10/2026, dự án F:\AI\T-Emulator\AndroidApp.

## Kết quả code và build

| Mục yêu cầu | Thay đổi | Bằng chứng |
|---|---|---|
| Edge-to-edge | Hai Activity gọi enableEdgeToEdge; xử lý system bars, cutout và IME; bỏ fitsSystemWindows xung đột; icon sáng trên nền tối | Mã nguồn, kiểm thử API 34/35/36 và ảnh device-results |
| API màu Window trong Material | Material 1.14.0; bỏ keep toàn bộ Material/AndroidX | Ba lớp Play nêu đều không còn trong DEX QA cuối |
| PiP | Nút/menu, feature check, tỷ lệ hợp lệ, sourceRect gameScreen; ẩn controls, auto-enter khi chơi; không auto-enter menu paused | Kiểm thử pixels native, restore, đóng, đổi ROM và lease |
| R8 / AGP | AGP 9.0.1, Gradle 9.1.0, built-in Kotlin + legacy-kapt, Room 2.8.5, minify/shrink, Ninja compile pool 2 | final-build.log, dex-audit/final-qa/summary.json |

VersionName **1.1.0**, versionCode **13**, compile/target SDK 36, min SDK 26.

Strict lint: abortOnError=true, checkReleaseBuilds=true, không disable MissingTranslation. Bổ sung 18 chuỗi tiếng Anh backup/restore/delete-account.

Lần build cuối: assembleDebug, packageReleaseBundle, packageRelease, lintDebug, lintRelease, testDebugUnitTest — **BUILD SUCCESSFUL**. Lint debug và release: **0 lỗi, 218 cảnh báo mỗi variant**. Unit tests: **68 test / 9 suite, 0 failure/error**. Không tuyên bố mọi cảnh báo lint đã hết.

## Các lỗi tìm thấy khi kiểm định độc lập

- JNI có một core toàn tiến trình. Khi mở ROM khác lúc game cũ còn PiP, Activity cũ có thể pause/stop core mới. Đã thêm singleTask/onNewIntent, teardown session cũ trước load mới, trả lease sau native stop. multi-before.txt tái hiện đứng hình; multi-final.txt xác nhận bản cuối chỉ có một render owner, frame ROM mới tiến triển và lease được trả.
- Android 12+ vừa bật PiP tự động vừa gọi entry ở onUserLeaveHint. Đã giới hạn callback entry cho API <31; nút PiP vẫn dùng được trên các API hỗ trợ.
- onPause có thể đến trước callback PiP tự động. Game đang chạy đủ điều kiện PiP giữ render đến onStop; menu paused vẫn dừng ngay ở onPause. Hồi quy bản cuối đã qua trên API 34/35/36.
- Test lookup View bị race khi Activity reinflate: lookup nay chạy UI thread. Test chờ Home/PiP animation hoàn tất trước restore và chờ callback destroyed khi đóng thay vì cố định một giây. Lỗi restore của harness ban đầu không được quy hoàn toàn cho code ứng dụng. Không thêm keep production chỉ để phục vụ reflection test.

## Kiểm thử thiết bị

AVD do tác vụ tạo: emulator-5560 API 34, emulator-5556 API 35, emulator-5558 API 36, x86_64.

**Hoàn tất 18/18 ca trên bản code cuối**, chạy lần lượt từng AVD theo lựa chọn của người dùng. Tất cả log kết quả được tạo sau build cuối; không dùng kết quả cũ để thay thế ca kiểm thử cuối. Kiểm tra log runtime không có FATAL EXCEPTION/Fatal signal.

| Android / API | Cử chỉ dọc | Cử chỉ ngang | Ba nút dọc | Ba nút ngang | Runtime R8 |
|---|---|---|---|---|---|
| Android 14 / 34 | PASS | PASS | PASS | PASS | PASS |
| Android 15 / 35 | PASS | PASS | PASS | PASS | PASS |
| Android 16 / 36 | PASS | PASS | PASS | PASS | PASS |

Ngoài 12 ca matrix và 3 ca R8: hole-cutout + gesture/landscape trên API 35, tall-cutout + ba nút/landscape trên API 36, và multi-session native ownership trên API 35 đều PASS. APK R8 QA ký bằng khóa debug, debuggable=false; smoke kiểm tra Room-backed screen, JNI bitmap thay đổi, PiP, restore controls và đóng Activity.

Bằng chứng: `device-results/status.json`, log từng thư mục thiết bị, `device-results/multi-final.txt`; SHA256 APK/debug/R8 và test harness trong `test-artifacts.json`. Ba AVD QA đã tắt sau kiểm thử để trả RAM. Người dùng chọn chỉ hoàn tất code và kiểm thử; cấu hình/signing production nằm ngoài đợt làm việc hiện tại.

Test dùng ROM NES tổng hợp tự tạo. Kiểm tra Room-backed Games/search, bàn phím, controls/insets, PiP button/Home, pixels thật thay đổi, restore, không trùng thread, menu paused+Home, native lease khi đổi ROM.

Ảnh landscape IME có chế độ bàn phím nhập toàn màn hình; không suy diễn bottom navigation app luôn hiện ở chế độ này. Chưa thử ARM thực, Android 8–13, Google/Firebase login, billing và backend production.

## DEX QA cuối

Nguồn: app/build/intermediates/intermediary_bundle/release/packageReleaseBundle/intermediary-bundle.aab.

SHA256: 6B8DAF85B0CCC05A3D3073AE91053147129AFA943139989ACD97735A6B7F2577.

| Chỉ số | Baseline | QA cuối |
|---|---:|---:|
| Lớp DEX | 10.493 | 6.124 |
| Callsite hai API màu Window | 10 | 4 |
| BottomSheetDialog trong DEX | Có | Không |
| EdgeToEdgeUtils trong DEX | Có | Không |
| SheetDialog trong DEX | Có | Không |

4 callsite còn thuộc AndroidX EdgeToEdgeApi26/29 cho nhánh tương thích trước Android 15. Không có callsite từ app hoặc ba lớp Material Play nêu. Không tuyên bố APK có 0 tham chiếu hai API. Lớp DEX giảm 41,6%; chưa đo heap/FPS để định lượng cải thiện hiệu năng.

## Thông tin cho đợt phát hành sau

**Chưa có AAB production sẵn sàng upload.** Intermediate QA cuối không ký, đóng gói với cấu hình đăng nhập trống để kiểm toán R8. app/build/outputs/bundle/release/app-release.aab do Antigravity tạo trước sửa PiP cuối dùng test/example config, không ký và đã cũ; không phát hành file này.

validateReleaseConfig vẫn giữ nguyên. final-release-config-check.log từ chối 5 trường thiếu:

- GOOGLE_WEB_CLIENT_ID
- BACKEND_VERIFY_URL (HTTPS)
- FIREBASE_API_KEY
- FIREBASE_APPLICATION_ID
- FIREBASE_PROJECT_ID

Cần cấu hình khóa upload hiện có qua RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD. Thiết lập trực tiếp trên máy; không gửi bí mật vào chat.

Automatic approval review từ chối trích cấu hình có thể nhạy cảm từ BuildConfig cũ thành JSON plaintext. Không tạo JSON đó, không dùng cách khác làm lại thao tác bị từ chối. Không upload/publish Google Play.

Sau khi môi trường được cấu hình: chạy bundleRelease với strict checks, xác minh chữ ký/version/DEX của đúng AAB đã ký. Google Play cần quét bản mới sau upload; kiểm tra local chưa chứng minh cảnh báo Console đã biến mất.

## Tham khảo

- [Android edge-to-edge](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [Android PiP entry và lifecycle](https://developer.android.com/develop/ui/views/picture-in-picture)
- [AGP 9.0](https://developer.android.com/build/releases/agp-9-0-0-release-notes)
- [Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Material releases](https://github.com/material-components/material-components-android/releases)



