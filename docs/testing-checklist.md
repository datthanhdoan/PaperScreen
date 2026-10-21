# Checklist kiểm thử v1 trên máy thật Android 16

Dựa trên bảng rủi ro R1–R12 của [feasibility-report.md](feasibility-report.md). Ghi kết quả vào cột cuối kèm tên máy, ROM, bản build (`versionName`). Lệnh `adb` giả định máy đã bật USB debugging.

Package: `io.github.datthanhdoan.paperscreen.debug` (bản CI) hoặc `io.github.datthanhdoan.paperscreen` (bản release). Bên dưới viết tắt là `$PKG`.

## 0. Chuẩn bị

- [ ] Tải APK từ artifact `PaperScreen-debug-N` của workflow Build hoặc từ GitHub Release.
- [ ] Ghi lại: hãng, model, bản ROM (One UI / HyperOS / Pixel), bản vá bảo mật, độ phân giải, `adb shell wm density`.
- [ ] Bật log: `adb logcat -s OverlayService OverlayLauncher BootReceiver WindowOverlayHost TileCache ActivityManager InputDispatcher`.

## 1. Rủi ro R1–R12

| # | Kiểm tra | Cách làm | Kỳ vọng | Kết quả |
|---|---|---|---|---|
| R1 | Chạm xuyên với window alpha 0.8 | Bật overlay. Chạm, cuộn, gõ trong 4–5 app (Chrome, Settings, Gmail, một game). `adb logcat \| grep -i "untrusted touch"`. `adb shell dumpsys window windows \| grep -A20 PaperScreen` xem `alpha=`. | Mọi thao tác hoạt động. Không có dòng `Untrusted touch`. Alpha ≤ 0.8. | |
| R1b | Kiểm tra ngược | `adb shell settings put global maximum_obscuring_opacity_for_touch 0.5`, tắt rồi bật lại overlay. Sau đó trả về `0.8`. | App tự hạ alpha xuống 0.5, chạm vẫn xuyên. Texture nhạt đi một chút vì tile tính theo alpha 0.8 lúc trước, đến khi đổi tham số hoặc khởi động lại service. | |
| R2 | Restricted Settings với APK mở từ file | Gỡ app. Tải APK bằng trình duyệt, mở bằng Files để cài. Vào app, bấm "Cấp quyền hiển thị". So sánh với `adb install`. | Nếu công tắc bị khóa: làm theo hướng dẫn trong app (Thông tin ứng dụng → ⋮ → Allow restricted settings) và cấp được quyền. Ghi lại ROM có khóa hay không. | |
| R3 | Tile bật FGS khi app ở nền | Vuốt app khỏi Recents. Kéo shade, thêm tile "Lớp giấy", bấm. Xem logcat `OverlayLauncher`. | Overlay hiện. Ghi lại nhánh nào chạy: start thẳng (không log), `startForegroundService refused` rồi bootstrap window, hay phải mở `OverlayStarterActivity`. | |
| R3b | Tile tắt | Bấm tile lần nữa. | Overlay biến mất, notification mất, tile về Inactive. Mở app thấy công tắc tắt. | |
| R4 | Khởi chạy lại sau boot | Bật overlay, `adb reboot`. Không mở app. | Overlay xuất hiện sau khi mở khóa. Logcat có `BootReceiver: ...BOOT_COMPLETED: start overlay -> STARTED` (hoặc `PENDING`). Không có `ForegroundServiceStartNotAllowedException` chưa bắt. | |
| R4b | Sau khi cập nhật app | Bật overlay, cài đè một APK mới hơn (cùng chữ ký). | `MY_PACKAGE_REPLACED` khởi chạy lại overlay. Ghi lại kết quả `STARTED` / `PENDING` / `FAILED`. | |
| R5 | Chi phí composition | `adb shell dumpsys SurfaceFlinger \| grep -B2 -A10 PaperScreen` khi đang cuộn web và khi xem video. So sánh `adb shell dumpsys gfxinfo <app đang dùng>` và pin sau 1 giờ đọc web, có và không có overlay. | Layer `PaperScreen` là `DEVICE` composition. Chênh lệch pin nhỏ. Ghi số liệu. | |
| R6 | Phủ cutout và vùng nav bar | Chụp màn hình ở dọc, ngang trái, ngang phải, với nav cử chỉ và nav 3 nút. Máy gập: gập và mở. `adb shell dumpsys window windows \| grep -A5 PaperScreen` xem `mFrame`. | Frame bằng toàn màn hình. Texture có ở vùng tai thỏ và phía sau nav bar trong suốt. Icon trên thanh hệ thống không có texture (bình thường). | |
| R7 | Video DRM | Mở Netflix, YouTube Premium hoặc Prime Video. | Ghi lại có thấy texture trên video không (nhìn bằng mắt). | |
| R8 | App ngân hàng, ví | Mở 2–3 app ngân hàng hoặc ví phổ biến ở VN khi đang bật overlay. | Ghi lại: app chạy bình thường, overlay tự ẩn (HIDE_OVERLAY_WINDOWS), hay app báo "màn hình bị che" và chặn thao tác. | |
| R9 | OEM kill FGS | Để máy qua đêm khi đang bật overlay, không cắm sạc. Samsung: kiểm tra "Deep sleeping apps". Xiaomi: Autostart, Battery saver. | Sáng hôm sau overlay vẫn còn. Nếu mất, ghi lại thiết lập pin đã dùng. | |
| R10 | Moiré, độ rõ chữ | Thử 3 mức độ thô (0, 50, 100) × 3 mức độ đậm (20, 45, 80). Chụp macro bằng máy ảnh khác. Đọc một bài dài ở cỡ chữ nhỏ. | Không lấp lánh hoặc viền màu ở mức mặc định. Chữ nhỏ vẫn rõ. Ghi lại mức nào bắt đầu khó đọc. | |
| R11 | Chữ ký ổn định giữa các build | So sánh SHA-256 ở bước *Print signing certificate* của hai lần chạy Build liên tiếp. Cài bản sau đè bản trước. | Cùng SHA-256 khi đã có `KEYSTORE_B64`. Cài đè thành công, giữ nguyên cài đặt. | |
| R12 | Toolchain và action | Xem log CI. | Đã chạy: AGP 9.4.1, Gradle 9.8.2, checkout@v7, setup-java@v6, setup-gradle@v6, upload-artifact@v7. Ghi lại cảnh báo deprecation nếu có. | |

## 2. Chức năng v1

| Kiểm tra | Cách làm | Kỳ vọng | Kết quả |
|---|---|---|---|
| Onboarding | Cài mới, mở app. | Hiện trạng thái thiếu quyền, nút cấp quyền và hướng dẫn Restricted settings. Công tắc bật bị vô hiệu cho đến khi có quyền. | |
| POST_NOTIFICATIONS | Sau khi có quyền overlay, bấm "Cho phép thông báo", thử cả Cho phép và Từ chối. | Từ chối thì overlay vẫn chạy, chỉ không thấy notification. | |
| Slider cập nhật ngay | Bật overlay, kéo từng slider. | Độ đậm và độ thô: texture đổi sau dưới 1 giây. Độ ấm và vignette: đổi tức thì. Không giật hay chớp màn hình. | |
| Đổi mẫu hạt | Bấm "Đổi mẫu hạt giấy". | Mẫu hạt đổi. | |
| Notification Tạm dừng | Bấm "Tạm dừng". | Overlay ẩn, notification đổi thành "Đã tạm dừng" với nút "Tiếp tục". Sau 5 phút overlay tự hiện lại. | |
| Notification Tắt | Bấm "Tắt". | Overlay và notification mất. Mở app thấy công tắc tắt. Reboot không tự bật lại. | |
| Kill process | `adb shell am kill $PKG` khi đang bật (hoặc force-stop qua Recents trên một số ROM). | START_STICKY khởi động lại service, overlay hiện lại với đúng thiết lập cũ. Ghi lại nếu ROM không restart. | |
| Thu hồi quyền khi đang chạy | Vào Settings, tắt "Hiển thị trên ứng dụng khác". | Overlay mất, không crash. Cấp lại quyền rồi bật lại được. | |
| Xoay màn hình (A6) | Xoay qua lại nhiều lần. | Overlay phủ kín theo hướng mới, không sinh lại tile (logcat `TileCache` không có dòng "Generated ..."). Vignette theo kích thước mới. | |
| Đổi density (A6) | Settings → Display size, đổi một nấc. Hoặc `adb shell wm density 480` rồi `adb shell wm density reset`. | Logcat `TileCache` có "Generated ... for <dpi mới>dpi in N ms". Hạt không bị phóng to hoặc nhòe. Ghi lại N (thời gian sinh tile). | |
| Dark mode (A6) | Bật và tắt giao diện tối khi overlay đang chạy, có và không có "Giảm tông ấm khi dùng giao diện tối". | Khi bật tùy chọn, tông ấm giảm rõ trên nền tối. Grain giữ nguyên. | |
| Long-press tile | Giữ tile trong shade. | Mở màn hình cài đặt của app. | |
| Ảnh chụp màn hình | Chụp màn hình khi overlay bật. | Ảnh có texture (hành vi đã biết). | |

## 3. Chưa xác minh trong phiên phát triển

Các điểm dưới đây chỉ được build và unit test trên CI, chưa chạy trên máy thật hay emulator:

- Toàn bộ hành vi runtime: overlay, chạm xuyên, notification, tile, boot, dark mode, xoay, density.
- Nhánh dự phòng khởi chạy FGS từ nền bằng cửa sổ bootstrap 1×1 (`OverlayLauncher`): chưa rõ cửa sổ 1×1 trong suốt có được Android 15+ tính là "overlay đang hiển thị" để miễn trừ hay không. Nếu không, tile sẽ rơi xuống nhánh mở activity, còn boot và cập nhật app sẽ thất bại (ghi `FAILED` vào log). Kết quả R3, R4, R4b sẽ trả lời câu hỏi này.
- Thời gian sinh tile (báo cáo ước tính 10–50 ms) chưa đo. Tile cache trong `cacheDir/tiles` làm lần khởi động lại sau nhanh hơn.
- Chất lượng hình ảnh của công thức tint B3 và mức vignette tối đa 5%.
