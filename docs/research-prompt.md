# Prompt nghiên cứu: PaperScreen (Android 16)

Dán nguyên khối bên dưới vào một phiên nghiên cứu (hoặc đưa cho agent nghiên cứu).

---

## Vai trò
Bạn là kỹ sư Android senior, đang nghiên cứu tính khả thi và thiết kế kỹ thuật cho một app **overlay texture giấy phủ toàn màn hình** trên Android. Hãy tìm kiếm sâu, ưu tiên tài liệu chính thức (developer.android.com, AOSP source, issuetracker) và source code của các repo open source thực tế. Mọi kết luận phải kèm nguồn; điều gì chưa xác minh được phải nói rõ là chưa xác minh.

## Bối cảnh
- Sản phẩm: app Android phủ lên toàn bộ màn hình một lớp texture giấy (noise, sợi giấy) cộng tint ấm, để màn hình trông như giấy. KHÔNG phải app đọc sách.
- Thiết bị mục tiêu: **Android 16 (API 36)**. Mức tối thiểu dự kiến: Android 8 (API 26).
- Ngôn ngữ/nền tảng: Kotlin native, build bằng Gradle trên **GitHub Actions** (người dùng không cài Android Studio). Cài bằng sideload debug APK.
- Repo: https://github.com/datthanhdoan/PaperScreen (license đã có).
- Tham khảo đã biết: `Piyush2407/paper-screen-android` (overlay texture, API 23), `Gregely/android-paper` (e-ink qua Accessibility, Android 14+), `Kelvanic/deckle` và `Umer-Hamaaz/Papersrc` (overlay texture trên desktop).

## Câu hỏi cần trả lời

### A. Cơ chế overlay trên Android 16
1. `TYPE_APPLICATION_OVERLAY` (quyền SYSTEM_ALERT_WINDOW) có còn hoạt động đầy đủ trên Android 16 không? Hạn chế nào về độ mờ/touch pass-through (ví dụ giới hạn alpha 0.8 từ Android 12, `FLAG_NOT_TOUCHABLE`, `setHideOverlayWindows`)?
2. Với một overlay chỉ để hiển thị, tổ hợp flag nào cho chạm xuyên qua hoàn toàn mà không bị app khác (ngân hàng, ví, màn hình cài đặt quyền) chặn hoặc cảnh báo "Screen overlay detected"?
3. Overlay phủ được lên status bar, navigation bar, notch/cutout, IME (bàn phím) không? Cần `FLAG_LAYOUT_NO_LIMITS`, `fitInsetsTypes`, `layoutInDisplayCutoutMode` thế nào cho edge-to-edge của Android 15/16?
4. Accessibility service (`TYPE_ACCESSIBILITY_OVERLAY`) có ưu điểm gì (phủ lên cả system UI, không bị giới hạn alpha) và đánh đổi gì (cảnh báo quyền, Google Play policy, "restricted settings" khi sideload)?
5. Hạn chế của foreground service trên Android 14–16: cần `foregroundServiceType` nào cho một overlay thường trực (`specialUse`?), quyền notification, giới hạn khởi chạy nền, Android 16 có thay đổi gì không?
6. Cách app sống sót khi bị hệ thống kill, khi đổi hướng màn hình, gập/mở máy gập, đổi độ phân giải, đổi dark mode?

### B. Kỹ thuật render texture
1. So sánh các cách tạo texture: (a) bitmap tileable sinh bằng code (Perlin/value noise, fiber), (b) ảnh tileable đóng gói trong assets, (c) AGSL `RuntimeShader` (API 33+), (d) `BitmapShader` + `Paint`. Cái nào hợp lý khi min API 26 mà target 16?
2. Chi phí pin/GPU của overlay tĩnh toàn màn hình; cách tránh vẽ lại liên tục (hardware layer, không `invalidate`).
3. Blend mode nào cho ra cảm giác giấy tốt nhất (multiply, overlay, soft-light) khi overlay chỉ có alpha compositing thường của WindowManager? Làm sao mô phỏng multiply bằng alpha và màu?
4. Công thức/công cụ để sinh texture giấy thuyết phục (grain, fiber, vignette nhẹ, tông kem), kèm tham số chỉnh được (độ đậm, độ thô, độ ấm).
5. Ảnh hưởng của texture lên độ rõ chữ, moiré trên màn hình OLED/PWM, và cách chọn mật độ hạt theo `densityDpi`.

### C. Cấp quyền và trải nghiệm người dùng
1. Luồng xin quyền "Display over other apps" trên Android 16 (intent `ACTION_MANAGE_OVERLAY_PERMISSION`), thông báo, và "restricted settings" cho APK sideload.
2. Quick Settings Tile (`TileService`) để bật/tắt nhanh; notification action; tự khởi động sau reboot (`BOOT_COMPLETED`) và giới hạn của nó trên Android 16.
3. Tự bật/tắt theo app đang mở (cần UsageStats hoặc Accessibility): lựa chọn nào nhẹ và ít quyền nhất?
4. Màn hình/ứng dụng nào cố tình ẩn overlay (secure windows, DRM video, màn hình nhập mật khẩu) và hành vi mong đợi.

### D. Build không cần môi trường local
1. Cấu hình GitHub Actions tối thiểu để build debug APK: JDK 17, `gradle/actions/setup-gradle`, `android-actions/setup-android` (hoặc runner đã có sẵn SDK), cache, upload artifact. Phiên bản AGP, Gradle, Kotlin tương thích với compileSdk 36.
2. Có nên commit Gradle wrapper (`gradlew`, `gradle-wrapper.jar`) và cách sinh nó mà không cần cài Gradle local?
3. Ký APK: debug keystore mặc định có đủ để sideload và nâng cấp bản cài đè không? Khi nào cần keystore riêng lưu trong GitHub Secrets?
4. Tạo release tự động (tag → GitHub Release kèm APK).

### E. So sánh với các giải pháp có sẵn
Đọc source (không chỉ README) của `Piyush2407/paper-screen-android` và `Gregely/android-paper`:
- Họ chọn window type/flag nào, xử lý lifecycle ra sao, có bug hay giới hạn nào đã biết?
- Có gì nên tái sử dụng về ý tưởng, và có gì nên tránh? Kiểm tra license trước khi đề xuất sao chép bất kỳ đoạn code nào.

## Định dạng đầu ra
1. **Kết luận ngắn (≤10 dòng):** nên dùng cơ chế overlay nào, min/target SDK, có khả thi không.
2. **Bảng so sánh** các phương án overlay (ứng dụng overlay vs accessibility) và phương án render texture, theo các tiêu chí: độ tương thích Android 16, quyền cần xin, độ phức tạp, pin, rủi ro.
3. **Kiến trúc đề xuất:** danh sách component (Service, View, Tile, Activity cài đặt, DataStore), flag `WindowManager.LayoutParams` cụ thể, manifest (permissions, service type).
4. **Rủi ro và điều chưa xác minh**, kèm cách kiểm thử trên máy thật.
5. **Danh sách nguồn** (link) cho từng kết luận quan trọng.

Không viết code app hoàn chỉnh ở bước này. Chỉ đưa snippet nhỏ khi cần minh họa một API hoặc cấu hình.
