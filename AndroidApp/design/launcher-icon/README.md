# T-Emulator Android Launcher Icon Design Source & Scripts

Thư mục này chứa file nguồn thiết kế vector và script sinh toàn bộ bộ tài nguyên launcher icon cho ứng dụng Android T-Emulator.

## 1. Danh sách file trong thư mục

- `controller.svg`: Nguồn thiết kế vector chính (108 × 108 dp, viewBox `0 0 108 108`), lớp ngoài trong suốt 100%, tay cầm trắng, cụm nút màu SNES và D-pad tối.
- `controller_monochrome.svg`: Nguồn vector một màu (monochrome silhouette với cutouts) dành cho Themed Icon trên Android 13+ (API 33+).
- `generate_launcher_icons.py`: Script tự động sinh toàn bộ tài nguyên:
  - Xuất `ic_launcher_foreground.xml` (VectorDrawable) vào `AndroidApp/app/src/main/res/drawable/`.
  - Xuất `ic_launcher_monochrome.xml` (VectorDrawable) vào `AndroidApp/app/src/main/res/drawable/`.
  - Cập nhật `mipmap-anydpi-v26/ic_launcher.xml` và `ic_launcher_round.xml`.
  - Thiết lập `mipmap-anydpi-v33/ic_launcher.xml` và `ic_launcher_round.xml` hỗ trợ themed icon.
  - Xóa các file raster foreground cũ (`ic_launcher_foreground.png`).
  - Sinh lại `ic_launcher.png` (bo góc) và `ic_launcher_round.png` (tròn) ở 5 density (mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi).
- `generate_comparison_artifacts.py`: Script tạo ảnh báo cáo trực quan `AndroidApp/docs/launcher-icon/before-after.png`.

## 2. Yêu cầu môi trường

- Python 3.10+ (đã kiểm thử trên Python 3.14.4).
- Các thư viện Python:
  ```powershell
  python -m pip install pillow resvg-py
  ```

## 3. Lệnh tái tạo toàn bộ tài nguyên

Chạy lệnh sau từ thư mục `AndroidApp/`:

```powershell
python design/launcher-icon/generate_launcher_icons.py
```

Sau khi sinh tài nguyên, tạo lại ảnh so sánh và kiểm chứng bằng:

```powershell
python design/launcher-icon/generate_comparison_artifacts.py
```

## 4. Thông số kỹ thuật thiết kế

- **Canvas Adaptive:** 108 × 108 dp (viewport 108 × 108).
- **Vùng hiển thị Launcher chuẩn:** 72 × 72 dp (tâm tại `54, 54`, margin ngoài 18 dp).
- **Vùng an toàn (Safe Zone):** Hình tròn tâm `(54, 54)`, bán kính 33 dp.
- **Kích thước tay cầm chọn lọc:** Rộng 60 dp, cao 33 dp (tỉ lệ chuẩn ~1.82 : 1).
- **Màu nền ứng dụng:** `#0C76FE` (`@color/ic_launcher_background`).
- **Màu nút hành động:**
  - Nút X (trên): `#0C76FE` (Signature Blue)
  - Nút A (phải): `#EF4444` (Vibrant Red)
  - Nút B (dưới): `#F59E0B` (Amber Yellow)
  - Nút Y (trái): `#10B981` (Emerald Green)
- **Màu D-Pad:** `#1E293B` (Slate-900), tâm `#0F172A`.
- **Màu Shoulder Bumpers (L/R):** `#2E3A48` (Slate-800).
