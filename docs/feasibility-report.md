# PaperScreen Android: báo cáo khả thi và thiết kế kỹ thuật

Ngày nghiên cứu: 2026-10-21. Phạm vi: đúng theo [research-prompt.md](research-prompt.md).
Quy ước: **[XM]** là đã xác minh từ tài liệu chính thức hoặc AOSP source (có link ở mục 5). **[CXM]** là chưa xác minh, cần thử trên máy thật.

---

## 1. Kết luận ngắn

1. **Khả thi.** Dùng `TYPE_APPLICATION_OVERLAY` (quyền SYSTEM_ALERT_WINDOW), đặt trong foreground service `specialUse`. Đây là cơ chế chính. [XM]
2. **Ràng buộc then chốt:** muốn chạm xuyên qua thì `LayoutParams.alpha` của cửa sổ phải **≤ 0.8**. Hệ thống xét alpha của *cửa sổ*, không xét alpha từng pixel. Với texture giấy, độ đậm thực tế chỉ khoảng 3–15%, nên giới hạn này không gây vấn đề. [XM]
3. **Giới hạn chấp nhận được:** app overlay nằm *dưới* bàn phím (IME), status bar, notification shade, navigation bar và màn hình khóa. Hệ thống cũng tự ẩn overlay trên trình cài đặt gói, hộp chọn app và các app ngân hàng dùng `setHideOverlayWindows`. Texture cũng hiện trong ảnh chụp màn hình. [XM]
4. Muốn phủ cả system UI và bàn phím thì chỉ có Accessibility overlay. Cái giá là cảnh báo "kiểm soát màn hình" sau 24 giờ, rủi ro chính sách Play và Restricted Settings khi sideload. Nên để làm **chế độ tùy chọn ở v2**, không dùng cho v1.
5. **SDK:** `minSdk 26`, `targetSdk 36`, `compileSdk 36`. Toolchain: AGP 9.4.x, Gradle 9.6.x trở lên, JDK 17, Kotlin dùng built-in của AGP 9.
6. **Render:** tile ALPHA_8 sinh bằng code (fBm noise và sợi giấy), vẽ bằng `BitmapShader(REPEAT)`, chỉ vẽ lại khi đổi tham số hoặc kích thước. Không dùng AGSL vì không cần với ảnh tĩnh.
7. **Ký APK:** debug keystore trên GitHub runner sinh mới ở mỗi lần build, nên **không cài đè được**. Bắt buộc dùng keystore cố định lưu trong GitHub Secrets.

---

## 2. Bảng so sánh

### 2.1 Cơ chế overlay

| Tiêu chí | **A. `TYPE_APPLICATION_OVERLAY` + FGS** (đề xuất) | **B. `TYPE_ACCESSIBILITY_OVERLAY`** (như android-paper) |
|---|---|---|
| Android 16 | Hoạt động đầy đủ. Layer 11 trong AOSP. [XM] | Hoạt động đầy đủ. Layer 31, cao nhất nhóm app có thể tạo. [XM] |
| Vùng phủ | Trên mọi activity. **Dưới** IME (13), status bar (15), shade (17), nav bar (24) và keyguard. [XM] | Phủ được status bar, nav bar, IME, shade. Phủ cả lock screen, nên android-paper phải tự gỡ cửa sổ khi khóa máy. [XM] |
| Giới hạn alpha khi chạm xuyên | Window alpha ≤ 0.8 (`getMaximumObscuringOpacityForTouch()`). [XM] | Không giới hạn vì là "trusted window". [XM] |
| Bị hệ thống ẩn | Có: khi cửa sổ phía trên đặt `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` (PackageInstaller, ResolverActivity, app có `HIDE_OVERLAY_WINDOWS`). [XM] | Không: `setForceHideNonSystemOverlayWindowIfNeeded` chỉ áp cho loại system-alert và toast. [XM] |
| Quyền | SYSTEM_ALERT_WINDOW (special access), FOREGROUND_SERVICE(_SPECIAL_USE), POST_NOTIFICATIONS (không bắt buộc). | Bật service trong Settings → Accessibility. POST_NOTIFICATIONS nếu cần thông báo. |
| Sideload, Restricted Settings | **Có thể bị chặn:** `OPSTR_SYSTEM_ALERT_WINDOW` nằm trong `PROTECTED_SETTINGS` của Enhanced Confirmation (AOSP main). [XM source, CXM hành vi trên từng ROM] | Bị chặn chắc chắn từ Android 13 với APK cài từ file. [XM] |
| Cảnh báo người dùng | Không có cảnh báo định kỳ. | Notification "view and control your screen" sau 24 giờ nếu không phải `isAccessibilityTool`. [XM] |
| Google Play | Overlay hợp lệ. `specialUse` cần giải trình khi review. | App không phục vụ người khuyết tật thì phải có prominent disclosure. Rủi ro bị từ chối cao. [XM] |
| Sống sót | FGS cộng overlay được hệ thống nâng importance. OEM vẫn có thể kill. | Hệ thống bind service, rất bền và tự chạy lại sau reboot. Service crash liên tục thì có thể bị tắt. [CXM] |
| Độ phức tạp | Thấp đến trung bình. | Trung bình. Thêm luồng onboarding Accessibility. |
| Pin | Như nhau: một layer composition toàn màn hình. | Như nhau, nếu chỉ hiển thị (không chụp màn hình như android-paper). |

### 2.2 Phương án render texture

| Tiêu chí | (a) Bitmap sinh bằng code + `BitmapShader` (**đề xuất**) | (b) Ảnh tileable trong assets | (c) AGSL `RuntimeShader` | (d) `BitmapShader`+`Paint` |
|---|---|---|---|---|
| Tương thích API 26–36 | Đầy đủ | Đầy đủ | Chỉ API 33+, cần fallback | Đầy đủ (chính là cách vẽ của a và b) |
| Chỉnh tham số (đậm, thô, ấm) | Liên tục, sinh lại tile trong khoảng 10–50 ms trên thread nền [CXM] | Chỉ chỉnh được alpha và tint. Độ thô cố định theo ảnh. | Liên tục, theo thời gian thực | Không áp dụng |
| Phụ thuộc mật độ màn hình | Sinh đúng theo `densityDpi` | Phải đóng gói nhiều bản hoặc scale (gây mờ) | Tính theo pixel | Không áp dụng |
| APK size | Gần như 0 | 100 KB – vài MB | 0 | Không áp dụng |
| Pin khi tĩnh | Vẽ một lần | Vẽ một lần | Vẽ một lần nếu không animate. Không có lợi thế gì. | Không áp dụng |
| Rủi ro | Phải tự viết noise tile liền mạch | Lặp pattern dễ thấy, bản quyền ảnh | Hai code path | Không áp dụng |

(d) là *cách vẽ*, không phải nguồn texture. (a) và (b) đều đi qua (d). Kết hợp hợp lý: (a) làm mặc định, cho phép thêm (b) như "preset ảnh" ở phiên bản sau.

---

## 3. Kiến trúc đề xuất

### 3.1 Component

| Component | Vai trò |
|---|---|
| `SettingsActivity` | Onboarding xin quyền, slider (độ đậm, độ thô, độ ấm, sợi, vignette), xem trước, nút bật/tắt. |
| `OverlayService` (FGS `specialUse`) | Giữ một `PaperView` trong `WindowManager`. Notification có action Tạm dừng / Tắt. `START_STICKY`. |
| `PaperView` (View) | `onDraw` vẽ tile bằng `BitmapShader`, sau đó lớp tint, sau đó vignette (`RadialGradient`). Không `invalidate` định kỳ. |
| `TextureGenerator` | Hàm thuần (unit-test được), chạy trên `Dispatchers.Default`. Nhận `(params, densityDpi)` và trả về `Bitmap` ALPHA_8 tileable. Cache ra file theo hash của params. |
| `PaperTileService` | Quick Settings tile bật/tắt. `ACTIVE_TILE` và `TOGGLEABLE_TILE`. |
| `BootReceiver` | `BOOT_COMPLETED` và `MY_PACKAGE_REPLACED`: nếu lần trước đang bật thì start service. |
| `SettingsRepository` (Jetpack DataStore Preferences) | Nguồn sự thật duy nhất. Service, Tile và Activity cùng `collect` một Flow. |
| (v2) `PaperAccessibilityService` | Tùy chọn "phủ cả thanh hệ thống và bàn phím". Dùng lại `PaperView`. Không đọc nội dung cửa sổ. |

Nên trừu tượng hóa phần "host cửa sổ" thành `OverlayHost { attach(); detach(); update() }`. Như vậy thêm chế độ Accessibility ở v2 không phải sửa phần render.

### 3.2 `WindowManager.LayoutParams`

```kotlin
val type = if (Build.VERSION.SDK_INT >= 26) TYPE_APPLICATION_OVERLAY else error("minSdk 26")
LayoutParams(MATCH_PARENT, MATCH_PARENT, type,
    FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or
    FLAG_LAYOUT_NO_LIMITS or FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT
).apply {
    // Quan trọng nhất: chạm xuyên chỉ được phép khi window alpha ≤ max obscuring opacity.
    alpha = if (Build.VERSION.SDK_INT >= 31)
        minOf(0.8f, inputManager.maximumObscuringOpacityForTouch) else 0.8f
    if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode =
        if (Build.VERSION.SDK_INT >= 30) LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; fitInsetsSides = 0 }
    gravity = Gravity.TOP or Gravity.START
    title = "PaperScreen"            // để dễ thấy trong `dumpsys window`
}
```

Ghi chú:
- **Không dùng `FLAG_SECURE`** cho overlay. Cờ này không giúp loại overlay khỏi ảnh chụp mà còn làm phần đó đen đi.
- Chỉ dùng **một** cửa sổ. Nhiều cửa sổ từ cùng UID sẽ cộng opacity theo công thức `1-(1-a)(1-b)` và dễ vượt 0.8. [XM]
- Từ API 30, nên `addView` qua `createWindowContext(display, TYPE_APPLICATION_OVERLAY, null)` thay vì context của Service. Như vậy `Configuration` và metrics đúng theo màn hình (quan trọng với máy gập). [XM API, CXM về mức độ cần thiết]
- Kiểm tra trên máy thật: `FLAG_LAYOUT_NO_LIMITS` cùng `MATCH_PARENT` có thật sự phủ phần dưới thanh điều hướng 3 nút và vùng cutout ở chế độ ngang không. [CXM]

### 3.3 Manifest (rút gọn)

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>

<service android:name=".OverlayService" android:exported="false"
    android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
      android:value="Persistent full-screen paper texture overlay drawn over other apps"/>
</service>

<service android:name=".PaperTileService" android:exported="true"
    android:icon="@drawable/ic_tile" android:label="@string/tile_label"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
  <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>
  <meta-data android:name="android.service.quicksettings.ACTIVE_TILE" android:value="true"/>
  <meta-data android:name="android.service.quicksettings.TOGGLEABLE_TILE" android:value="true"/>
</service>

<receiver android:name=".BootReceiver" android:exported="true">
  <intent-filter>
    <action android:name="android.intent.action.BOOT_COMPLETED"/>
    <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
  </intent-filter>
</receiver>
```

### 3.4 Trả lời chi tiết theo câu hỏi

#### A. Cơ chế overlay

**A1.** `TYPE_APPLICATION_OVERLAY` vẫn hoạt động đầy đủ trên Android 16. Trang behavior changes của Android 16 không có thay đổi nào về overlay. [XM] Các hạn chế:
- Từ Android 12, chạm xuyên qua cửa sổ `FLAG_NOT_TOUCHABLE` chỉ được giao cho app bên dưới khi thỏa một trong các điều kiện: cùng UID; cửa sổ trusted; cửa sổ INVISIBLE/GONE; `alpha == 0`; hoặc *một* cửa sổ SAW có `alpha ≤ 0.8`. Nếu không thỏa, touch bị chặn và logcat ghi `Untrusted touch due to occlusion by …`. [XM]
- Hệ thống tính bằng `LayoutParams.alpha`, **không** tính alpha từng pixel. Cửa sổ `alpha=1.0` mà toàn pixel trong suốt vẫn chặn touch.
- `setHideOverlayWindows(true)` (API 31, quyền `HIDE_OVERLAY_WINDOWS`) cho phép app khác ẩn overlay của ta trên cửa sổ của họ. Không có cách nào vượt qua, và cũng không nên tìm cách vượt qua. [XM]

**A2.** Tổ hợp chạm xuyên: `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` kết hợp `alpha ≤ 0.8`. Với app ngân hàng hoặc ví: nếu họ dùng `setHideOverlayWindows`, overlay **tự biến mất** khi họ ở trên cùng rồi hiện lại khi họ rời đi. Ta không cần xử lý gì. Hộp thoại "Screen overlay detected" là hành vi của Android 6/7 khi cấp quyền runtime. Trên bản hiện đại, trình cài đặt và chooser **ẩn** overlay thay vì cảnh báo (đã thấy trong AOSP `PackageInstallerActivity` và `ResolverActivity`). [XM] Hộp thoại cấp quyền của PermissionController có làm vậy không thì chưa xác minh được source. [CXM] Một số app ngân hàng tự phát hiện overlay (qua `MotionEvent.FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`) và từ chối thao tác. Ta không kiểm soát được việc này. Nên có danh sách "tự tắt khi mở app X" (C3). [CXM]

**A3.** App overlay **không** phủ được status bar, nav bar, IME hay shade, vì thứ tự layer cố định trong `WindowManagerPolicy.getWindowLayerFromTypeLw`. Cờ nào cũng không thay đổi được thứ tự này. [XM] Notch/cutout thì phủ được bằng `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (API 30+), `fitInsetsTypes = 0` và `FLAG_LAYOUT_NO_LIMITS`. Edge-to-edge của Android 15/16 áp cho *activity*. Với cửa sổ overlay, chỉ cần không fit insets. [XM cho activity; với overlay thì CXM] Lưu ý thực tế: vì status bar và nav bar của Android 15+ trong suốt, nội dung app phía sau *có* texture, còn icon trên thanh thì không có. Thường khó nhận ra.

**A4.** Accessibility overlay là trusted window, nên không bị giới hạn alpha, không bị `HIDE_OVERLAY_WINDOWS` ẩn và nằm trên mọi system UI. Đánh đổi: (1) Restricted Settings với APK sideload, người dùng phải vào App info, menu ⋮, chọn "Allow restricted settings"; (2) notification cảnh báo sau 24 giờ; (3) Play policy chỉ cho `isAccessibilityTool` với app phục vụ người khuyết tật, app này thì không đủ điều kiện; (4) phủ cả lock screen, nên phải tự gỡ cửa sổ khi `ACTION_SCREEN_OFF` (android-paper làm đúng như vậy). Nếu làm v2, service chỉ cần cấu hình `accessibilityEventTypes` tối thiểu, `canRetrieveWindowContent="false"`, và chỉ dùng để sở hữu cửa sổ.

**A5.** FGS:
- `foregroundServiceType="specialUse"` kèm quyền `FOREGROUND_SERVICE_SPECIAL_USE` và `<property PROPERTY_SPECIAL_USE_FGS_SUBTYPE>`. Không có runtime prerequisite. `systemExempted` không dùng được vì chỉ dành cho VPN, device admin, v.v. [XM]
- **Android 15+:** quyền SAW chỉ là ngoại lệ cho việc khởi chạy FGS từ nền khi app *đang có* một cửa sổ overlay hiển thị. Vì vậy thứ tự đúng là: khởi chạy từ Activity hoặc thao tác người dùng (Tile, notification). Nếu phải khởi chạy từ nền thì add overlay trước, chờ `onWindowVisibilityChanged(VISIBLE)`, rồi mới `startForeground`. [XM]
- `BOOT_COMPLETED` là ngoại lệ khởi chạy nền. Android 15 chặn các loại `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection`, `microphone` từ boot. **`specialUse` không nằm trong danh sách này.** [XM] Vẫn nên thử trên máy thật. [CXM]
- POST_NOTIFICATIONS **không** bắt buộc để chạy FGS. Nếu người dùng từ chối, notification bị ẩn nhưng service vẫn chạy (còn thấy trong Task Manager). [XM] Từ Android 14, người dùng vuốt bỏ được notification FGS.
- Android 16 thêm quota cho job, không ảnh hưởng FGS thuần. Android 16 không có thay đổi FGS nào khác liên quan. [XM]

**A6.** Sống sót và thay đổi cấu hình:
- *Bị kill:* `START_STICKY`, đọc lại trạng thái từ DataStore trong `onCreate`. Trên Samsung, Xiaomi, Oppo, nên hướng dẫn tắt tối ưu pin. Ngoại lệ "Battery optimizations turned off" cũng mở thêm quyền khởi chạy FGS từ nền. [XM]
- *Xoay, gập/mở, đổi độ phân giải:* cửa sổ `MATCH_PARENT` tự resize. `PaperView.onSizeChanged` chỉ tính lại vignette, tile vẫn dùng lại được. Khi `densityDpi` đổi (Display size hoặc màn hình phụ của máy gập) thì sinh lại tile. Dùng `DisplayManager.DisplayListener` hoặc `ComponentCallbacks.onConfigurationChanged` của window context.
- *Dark mode:* `onConfigurationChanged` kiểm tra `uiMode`. Trên nền tối, lớp làm tối gần như không thấy được, còn lớp tint sẽ nâng màu đen lên. Nên có profile riêng cho dark mode (giảm tint, giữ grain), hoặc tùy chọn "tự giảm khi dark mode".
- Overlay không cần hiện khi tắt màn hình. Có thể bỏ qua, vì app overlay nằm dưới keyguard.

#### B. Render texture

**B1.** Xem bảng 2.2. Chọn (a): tile ALPHA_8 kích thước 256–512 px vật lý, `BitmapShader(TileMode.REPEAT, REPEAT)`. Paint có `color` là màu "mực giấy" (nâu sẫm). Shader trên bitmap ALPHA_8 dùng màu của Paint cho phần màu, nên đổi độ ấm chỉ cần đổi `paint.color`, không phải sinh lại tile. [XM theo tài liệu `Paint`/`BitmapShader`; CXM hiệu năng]

**B2.** Pin và GPU:
- View chỉ `invalidate()` khi đổi params hoặc size. Không có animation nào.
- Chi phí còn lại là *composition*: SurfaceFlinger phải trộn một layer toàn màn hình trong suốt *mỗi khi màn hình bên dưới cập nhật*. Nếu HWC còn plane trống, chi phí gần như bằng 0. Nếu hết plane, SurfaceFlinger chuyển sang GPU composition, khi đó tốn hơn rõ rệt khi cuộn hoặc xem video ở 120 Hz. [CXM, cần đo]
- Đo bằng: `adb shell dumpsys SurfaceFlinger` (xem layer `PaperScreen` là `DEVICE` hay `CLIENT` composition), `dumpsys batterystats`, và so sánh `dumpsys gfxinfo` khi bật và tắt.
- Bộ nhớ: buffer cửa sổ 1440×3120×4 B ≈ 18 MB (×2–3 nếu BufferQueue giữ nhiều buffer). Tile ALPHA_8 512² chỉ 256 KB. Không giữ bitmap toàn màn hình trong heap.
- `setLayerType(LAYER_TYPE_HARDWARE)` không có lợi khi nội dung tĩnh và chỉ có một view. Không cần dùng.

**B3.** Blend mode: WindowManager chỉ có alpha compositing thường (`out = src·a + dst·(1−a)`). Overlay, soft-light và color-dodge phụ thuộc phi tuyến vào `dst`, nên **không mô phỏng được**. Riêng multiply thì mô phỏng được:
- **Multiply xám chính xác:** muốn `out = dst·t` (t ∈ [0,1] là độ sáng texture) thì dùng src = đen, a = 1−t: `out = 0·a + dst·(1−a) = dst·t`. Vì vậy tile ALPHA_8 lưu "độ tối" `1−t` vẽ bằng màu đen chính là multiply. Màu nâu rất sẫm cho cảm giác ấm hơn, sai số nhỏ ở vùng tối.
- **Multiply màu ấm m=(mr,mg,mb) gần đúng:** chọn `a = max(1−m)` (kênh xanh dương), `C = 1 − (1−m)/a`. Kết quả chính xác trên nền trắng, còn màu đen bị nâng lên một lượng `C·a`. Ví dụ m=(1.00, 0.96, 0.88): a=0.12, C=(1.00, 0.67, 0.00) (cam), đen bị nâng lên (0.12, 0.08, 0). Trên OLED, việc này cũng làm pixel đen sáng lên (tốn pin hơn một chút). Nên có preset tint nhẹ, và gợi ý người dùng dùng **Night Light / Eye comfort** của hệ thống cho tông ấm. Night Light là multiply thật bằng color matrix trong display pipeline. [CXM: chưa đo chất lượng hình ảnh]
- Lưu ý: window alpha 0.8 nhân thêm vào mọi pixel. Hãy tính độ đậm mong muốn rồi chia cho 0.8 khi sinh tile.

**B4.** Công thức texture (mỗi pixel trong tile, tọa độ tuần hoàn để liền mạch):
- *Grain:* fBm của value noise tuần hoàn (lưới chu kỳ = kích thước tile), 3–4 octave, `persistence ≈ 0.5`. Tần số cơ sở tính từ tham số **độ thô** (kích thước hạt theo dp → px).
- *Sợi:* rải N đoạn cong ngắn (Bézier, dài 2–12 mm quy theo dpi, mảnh 0.5–1 px) với hướng ngẫu nhiên, alpha thấp, vẽ có wrap-around qua mép tile.
- *Đốm, mottling:* noise tần số rất thấp, biên độ nhỏ.
- *Kết hợp:* `dark = clamp(k_g·grain + k_f·fiber + k_m·mottle)` × **độ đậm**, ghi vào ALPHA_8.
- *Vignette:* không đưa vào tile vì sẽ lặp lại. Vẽ riêng bằng `RadialGradient` theo kích thước view, biên độ ≤ 5%.
- *Tông kem:* lớp `drawColor(C, alpha a)` theo công thức B3, điều khiển bằng tham số **độ ấm**.
- *Seed* cố định để kết quả lặp lại được và test được.
- Tham khảo thêm `feTurbulence` của SVG, cách các shader "paper" trên Shadertoy phối noise. Repo desktop `deckle` và `Papersrc` chưa được đọc trong lần nghiên cứu này. [CXM]

**B5.** Độ rõ chữ, moiré, mật độ hạt:
- Biên độ grain nên ≤ 3–6% luminance. Lớn hơn thì chữ nhỏ mất nét và giảm tương phản. [CXM, khuyến nghị thực nghiệm]
- Hạt kích thước 1 px vật lý trên màn OLED PenTile/diamond sẽ gây viền màu và lấp lánh. Nên để hạt nhỏ nhất ≈ 1.5–2 px vật lý, tức là quy theo `densityDpi`: `grainPx = grainDp × dpi / 160`.
- Tránh pattern tuần hoàn rõ: dùng tile ≥ 512 px, có thể ghép hai tile với kích thước nguyên tố cùng nhau (ví dụ 509 và 512).
- Texture đứng yên trong khi nội dung cuộn bên dưới, giống miếng dán màn hình nhám. Đây là bản chất của overlay, không khắc phục được.
- Overlay không làm thay đổi PWM. Nhưng nếu người dùng giảm độ sáng hệ thống để bù lại độ tối của overlay thì PWM có thể bị ảnh hưởng. Nên giữ độ đậm thấp. [CXM]

#### C. Quyền và UX

**C1.** `Settings.canDrawOverlays(ctx)` → `startActivity(Intent(ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))`. Từ Android 11, Uri package **bị bỏ qua** và hệ thống mở danh sách chung. [XM] Bọc lệnh trong `try/catch ActivityNotFoundException` theo khuyến nghị trong tài liệu. Kiểm tra lại quyền trong `onResume`.
- **Restricted Settings:** trong AOSP main, `OPSTR_SYSTEM_ALERT_WINDOW` thuộc `PROTECTED_SETTINGS` của `EnhancedConfirmationService`. Một gói bị coi là guarded khi `packageSource` là `LOCAL_FILE` hoặc `DOWNLOADED_FILE` (mở APK từ file manager hoặc trình duyệt). Khi đó công tắc "Display over other apps" có thể bị khóa, người dùng phải vào App info, menu ⋮, chọn "Allow restricted settings". [XM source; CXM về việc Android 16 release và ROM của từng hãng có bật hay không] Onboarding cần hướng dẫn sẵn trường hợp này.
- POST_NOTIFICATIONS xin sau khi đã có overlay, kèm giải thích. Không bắt buộc.

**C2.**
- **Tile:** `onStartListening` đọc state. `onClick` bật/tắt. Từ API 34 phải dùng `startActivityAndCollapse(PendingIntent)`, overload nhận `Intent` sẽ ném exception. [XM] Tài liệu không nói rõ tile click có thuộc ngoại lệ khởi chạy FGS từ nền hay không. [CXM] Khuyến nghị: thử `startForegroundService` trước. Nếu gặp `ForegroundServiceStartNotAllowedException` thì add overlay trước (đã có SAW và cửa sổ visible, khớp ngoại lệ của Android 15) rồi mới start. Phương án dự phòng cuối cùng là mở Activity trong suốt qua `startActivityAndCollapse(PendingIntent)`.
- **Notification action:** dùng `PendingIntent.getService` hoặc `getForegroundService` với `FLAG_IMMUTABLE`. Action từ notification là "user action", thuộc ngoại lệ. [XM]
- **Boot:** xem A5. Nhớ rằng người dùng phải mở app ít nhất một lần sau khi cài thì mới nhận được `BOOT_COMPLETED` (app ở trạng thái stopped). [XM, quy tắc chung của Android]

**C3.** Tự bật/tắt theo app:
- **UsageStats** (`PACKAGE_USAGE_STATS`, special access): không có callback, phải poll `queryEvents` khoảng 1–2 giây một lần khi màn hình bật. Tốn pin nhẹ. `OPSTR_GET_USAGE_STATS` **cũng thuộc ECM** nên bị Restricted Settings giống SAW. [XM source]
- **Accessibility:** có sự kiện `TYPE_WINDOW_STATE_CHANGED` tức thì nhưng quyền nặng (A4).
- **Khuyến nghị:** v1 không làm. v2 dùng UsageStats với poll thưa, chỉ chạy khi danh sách loại trừ khác rỗng. Nếu người dùng đã bật chế độ Accessibility thì dùng luôn sự kiện từ đó.

**C4.** Ai ẩn hoặc chặn overlay:
- Cửa sổ có `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`: overlay bị hệ thống ẩn rồi tự hiện lại. Gồm trình cài đặt gói, chooser/resolver, app có `HIDE_OVERLAY_WINDOWS` (ngân hàng, ví). [XM]
- `FLAG_SECURE` (mật khẩu, ngân hàng, Netflix): **không** ẩn overlay, vì cờ này chỉ chặn chụp và chiếu màn hình. Overlay vẫn hiển thị. [XM về định nghĩa cờ; CXM về video DRM qua hardware overlay plane]
- Lock screen, shade, IME: nằm trên overlay (A3).
- Ảnh chụp và quay màn hình **có** texture. Hướng giảm thiểu: nút "Tạm dừng 5 phút" trên tile và notification. [CXM]

#### D. Build không cần môi trường local

**D1.** Runner `ubuntu-24.04` đã có sẵn Android SDK (platforms 34–37, build-tools 36.0.0/36.1.0, `ANDROID_HOME`), nên `android-actions/setup-android` **không bắt buộc**. [XM] Phiên bản tương thích:
- **Khuyến nghị:** AGP **9.4.1** (bản ổn định mới nhất trên Google Maven), Gradle ≥ 9.6.0 (bản mới nhất là 9.8.2), JDK 17, SDK Build Tools 36.0.0. Kotlin dùng **built-in của AGP 9**: bỏ plugin `org.jetbrains.kotlin.android`, cấu hình `kotlin { compilerOptions {} }`. [XM] Phiên bản KGP mà AGP 9.4 kéo theo chưa xác minh. [CXM]
- **Phương án bảo thủ:** AGP 8.13.2 (hỗ trợ tối đa API 36.1), Gradle 8.13 trở lên, Kotlin 2.2 hoặc 2.3. [XM]

```yaml
# .github/workflows/build.yml (rút gọn)
on: { push: { branches: [master] }, pull_request: {}, workflow_dispatch: {} }
jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v7          # major theo README setup-android; [CXM] kiểm tra lại
      - uses: actions/setup-java@v6
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v6   # có cache sẵn
      - run: ./gradlew assembleDebug testDebugUnitTest
      - uses: actions/upload-artifact@v4
        with: { name: PaperScreen-debug, path: app/build/outputs/apk/debug/*.apk }
```

**D2.** **Có, nên commit** `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` và `gradle-wrapper.properties`. Thiếu `gradle-wrapper.jar` thì `./gradlew` không chạy được. Repo Piyush2407 đang thiếu đúng file này. Cách sinh wrapper mà không cài Gradle: một workflow `workflow_dispatch` dùng `setup-gradle` với `gradle-version: '9.6.1'`, chạy `gradle wrapper --gradle-version 9.6.1`, rồi upload artifact hoặc tạo PR chứa các file wrapper. Thêm job `gradle/actions/wrapper-validation` để kiểm tra checksum của jar. [XM cho `gradle-version`; workflow sinh wrapper là CXM]

**D3.** Ký APK:
- Debug keystore nằm ở `~/.android/debug.keystore` và **được sinh mới trên mỗi runner sạch**. Mỗi build có chữ ký khác nhau, nên cài đè bị lỗi `INSTALL_FAILED_UPDATE_INCOMPATIBLE` và phải gỡ app (mất cài đặt). [CXM về việc runner có sẵn keystore hay không. Trong runner-images README không thấy liệt kê, nên coi như không có.]
- **Giải pháp:** tạo một keystore một lần (có thể bằng chính workflow, `keytool` có sẵn trong JDK), lưu base64 vào Secret `KEYSTORE_B64` cùng các mật khẩu. Workflow decode ra file và `signingConfigs` đọc từ biến môi trường. Áp cho cả debug và release. Bản build từ PR của fork không đọc được secrets, nên với PR fallback về debug key tạm thời.

**D4.** Release: `on: push: tags: ['v*']`, build `assembleRelease` (ký bằng keystore ở D3), rồi `softprops/action-gh-release` (hoặc `gh release create` có sẵn trên runner) đính kèm APK. `versionCode` lấy từ `github.run_number`, `versionName` lấy từ tag.

#### E. Repo tham khảo

**`Piyush2407/paper-screen-android`** (commit `5113186`, 2026-10-12):
- Repo **không có source app**. `app` là một *file rỗng* 1 dòng. Không có `AndroidManifest`, Kotlin hay Java, không có `.github/workflows/build-apk.yml` như README mô tả, không có `gradle-wrapper.jar`. Chỉ có README, `build.gradle` gốc 2 dòng, `settings.gradle`, `gradlew` và `.gitignore`. Vì vậy không đọc được window type hay flag nào. Mọi điều README nói đều chưa kiểm chứng được.
- Ý tưởng trong README là "target API 23 để tránh quy tắc FGS và notification". Ý tưởng này **không còn dùng được**: Android 14 chặn cài app có targetSdk < 23, Android 15 nâng mốc lên < 24. Vậy APK target 23 không cài được trên Android 15/16 (trừ khi dùng `adb install --bypass-low-target-sdk-block`). [XM]
- Ý tưởng nên giữ: ba nhóm hiệu ứng độc lập (Texture / Light Tint / Plain White), mỗi nhóm có slider riêng nhưng gộp vào **một** cửa sổ overlay. Cách này khớp với ràng buộc cộng opacity.
- License: GitHub API trả `license: null`, không có file LICENSE, tức là mặc định *all rights reserved*. **Không sao chép code hay asset** (texture).

**`Gregely/android-paper`** (commit `37677ee`, 2026-09-29; package `com.paperscreen`, khoảng 5.000 dòng Kotlin):
- Window: `TYPE_ACCESSIBILITY_OVERLAY`, flags `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_HARDWARE_ACCELERATED`, `OPAQUE` khi có thể (cho phép compositor bỏ qua lớp bên dưới), `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, `fitInsetsTypes = 0`, `fitInsetsSides = 0` (`OverlayWindow.kt:82-99`).
- Cơ chế: e-ink giả lập bằng cách chụp từng cửa sổ (`takeScreenshotOfWindow`, API 34+) rồi lọc LUT. Vì vậy cần `canTakeScreenshot` và `canRetrieveWindowContent`, nặng hơn nhiều so với nhu cầu của ta. Có giới hạn 333 ms mỗi lần chụp. min, target và compileSdk đều là 34. AGP 8.10.1, Kotlin 2.1.21, Gradle 8.14.3.
- Lifecycle đáng học: đăng ký `ACTION_SCREEN_OFF/ON/USER_PRESENT` và gỡ overlay khi khóa máy; dùng `DisplayManager.DisplayListener` để xử lý xoay và đổi độ phân giải; notification thường (không phải FGS) với Stop và "Pause 5 min", trạng thái tạm dừng lưu bền qua việc process bị restart; xử lý app secure bằng thông báo nhỏ rồi tự quay lại.
- Giới hạn đã biết (theo README): không chụp được cửa sổ `FLAG_SECURE`; wallpaper không có trong danh sách cửa sổ; cần grace period 1.5 s sau khi mở khóa.
- Nên tránh: phụ thuộc Accessibility cho tính năng lõi (rủi ro Play và Restricted Settings); minSdk 34 (quá cao so với mục tiêu 26).
- License: `license: null`, không có file LICENSE, tức là *all rights reserved*. **Chỉ học ý tưởng, không sao chép code.** README có mô tả chi tiết, cứ học theo mô tả rồi tự viết lại.

---

## 4. Rủi ro, điều chưa xác minh và cách thử

| # | Rủi ro / CXM | Cách kiểm thử trên máy thật Android 16 |
|---|---|---|
| R1 | Chạm có thật sự xuyên qua với `alpha=0.8` trên ROM của hãng (One UI, HyperOS) không | Bật overlay, chạm vào nhiều app. `adb logcat \| grep "Untrusted touch"`. Thử thêm `alpha=1.0` để xác nhận touch bị chặn (kiểm tra ngược). |
| R2 | Restricted Settings có khóa công tắc SAW với APK mở từ file không | Cài bằng cách mở APK trong Files, sau đó vào Settings → Display over other apps. So sánh với `adb install` (package source khác). Ghi lại theo ROM. |
| R3 | Tile `onClick` → `startForegroundService` có bị `ForegroundServiceStartNotAllowedException` không | Khi app không ở foreground, kéo shade xuống và bấm tile. Xem logcat. Thử cả hai thứ tự: add overlay trước và start FGS trước. |
| R4 | `specialUse` khởi chạy từ `BOOT_COMPLETED` | `adb reboot`. Kiểm tra overlay có xuất hiện và có exception trong logcat không. |
| R5 | Chi phí composition (HWC hay GPU) | `adb shell dumpsys SurfaceFlinger` xem cột composition của layer `PaperScreen`. So sánh pin trong 1 giờ đọc web, bật và tắt overlay. |
| R6 | Overlay có phủ hết cutout và vùng nav 3 nút, ở chế độ ngang và khi gập/mở không | Chụp màn hình ở từng tư thế. `dumpsys window windows \| grep -A5 PaperScreen` xem frame của cửa sổ. |
| R7 | Video DRM (Netflix, YouTube Premium) có hiện texture không | Mở video. Nhìn bằng mắt vì không chụp màn hình được. |
| R8 | App ngân hàng từ chối thao tác vì "obscured" | Thử 2–3 app ngân hàng hoặc ví phổ biến tại VN khi đang bật overlay. |
| R9 | OEM kill FGS (Samsung "Deep sleeping apps", Xiaomi Autostart) | Để máy qua đêm, kiểm tra overlay còn không. Thêm hướng dẫn theo từng hãng (tham khảo dontkillmyapp.com). |
| R10 | Moiré và độ rõ chữ | Chụp macro màn hình bằng máy ảnh khác. Cho vài người đọc thử ở 3 mức độ thô và 3 mức độ đậm. |
| R11 | Debug keystore trên runner | Build hai lần liên tiếp, `apksigner verify --print-certs` so sánh SHA-256 của hai bản. |
| R12 | Phiên bản KGP mà AGP 9.4 kéo theo, và version các action (`checkout`, `setup-java`, `upload-artifact`) | Kiểm tra lại khi viết workflow thật. Chạy thử. |

---

## 5. Nguồn

**Overlay và touch**
- FLAG_NOT_TOUCHABLE, quy tắc chạm, 0.8, công thức cộng opacity, TYPE_APPLICATION_OVERLAY nằm dưới status bar và IME: https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- Untrusted touch events (Android 12), lệnh adb để test: https://developer.android.com/about/versions/12/behavior-changes-all
- `Window.setHideOverlayWindows` / `HIDE_OVERLAY_WINDOWS`: https://developer.android.com/reference/android/view/Window , https://developer.android.com/security/fraud-prevention/activities
- Thứ tự layer theo loại cửa sổ (AOSP): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/WindowManagerPolicy.java
- Ẩn overlay không phải của hệ thống, chỉ áp cho system-alert và toast (AOSP `WindowState.setForceHideNonSystemOverlayWindowIfNeeded`): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowState.java
- PackageInstaller và Resolver ẩn overlay: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/PackageInstaller/src/com/android/packageinstaller/PackageInstallerActivity.java , https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/ResolverActivity.java
- `createWindowContext`: https://developer.android.com/reference/android/content/Context

**Foreground service và hành vi theo phiên bản**
- Loại `specialUse` và `systemExempted`: https://developer.android.com/develop/background-work/services/fgs/service-types
- Ngoại lệ khởi chạy FGS từ nền (SAW kèm cửa sổ hiển thị, boot, notification): https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android 15: các loại FGS bị chặn từ BOOT_COMPLETED, điều kiện SAW mới, edge-to-edge và cutout: https://developer.android.com/about/versions/15/behavior-changes-15
- Android 15: không cài được targetSdk < 24: https://developer.android.com/about/versions/15/behavior-changes-all
- Android 14: không cài được targetSdk < 23: https://developer.android.com/about/versions/14/behavior-changes-all
- Android 16 (mọi app, và app target 36): https://developer.android.com/about/versions/16/behavior-changes-all , https://developer.android.com/about/versions/16/behavior-changes-16
- POST_NOTIFICATIONS và FGS: https://developer.android.com/develop/ui/views/notifications/notification-permission

**Quyền, Accessibility, Restricted Settings**
- `ACTION_MANAGE_OVERLAY_PERMISSION`: https://developer.android.com/reference/android/provider/Settings
- Enhanced Confirmation: `PROTECTED_SETTINGS` gồm SAW và GET_USAGE_STATS, điều kiện guarded: https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/service/java/com/android/ecm/EnhancedConfirmationService.java
- Restricted settings (bài viết thứ cấp, không chính thức): https://androidauthority.com/android-15-enhanced-confirmation-mode-3436697 , https://docs.lawnchair.app/troubleshooting/restricted-settings
- Notification cảnh báo Accessibility sau 24 giờ (AOSP): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/PolicyWarningUIController.java
- Chính sách Play về AccessibilityService API: https://support.google.com/googleplay/android-developer/answer/10964491
- TileService (`startActivityAndCollapse(PendingIntent)`, ACTIVE_TILE, TOGGLEABLE_TILE): https://developer.android.com/reference/android/service/quicksettings/TileService

**Build**
- Ghi chú phát hành AGP (9.4, Gradle 9.6.0, JDK 17, API 37): https://developer.android.com/build/releases/gradle-plugin
- AGP 8.13 (tối đa API 36.1): https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes
- Built-in Kotlin trong AGP 9: https://developer.android.com/build/migrate-to-built-in-kotlin
- Bảng tương thích Kotlin và AGP: https://developer.android.com/build/kotlin-support
- Danh sách phiên bản Gradle: https://services.gradle.org/versions/all
- Danh sách phiên bản AGP: https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- `gradle/actions/setup-gradle@v6`: https://github.com/gradle/actions/blob/main/docs/setup-gradle.md
- Android SDK có sẵn trên runner Ubuntu 24.04: https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md
- `android-actions/setup-android`: https://github.com/android-actions/setup-android

**Repo tham khảo** (đã clone và đọc source)
- https://github.com/Piyush2407/paper-screen-android (commit 5113186)
- https://github.com/Gregely/android-paper (commit 37677ee): `OverlayWindow.kt`, `PaperScreenAccessibilityService.kt`, `AndroidManifest.xml`, `accessibility_service_config.xml`, `README.md`
- Chưa đọc: `Kelvanic/deckle`, `Umer-Hamaaz/Papersrc` (desktop).
