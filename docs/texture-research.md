# Texture giấy: nghiên cứu và thiết kế lại (v0.2)

Tài liệu này bổ sung mục B1–B5 của `docs/feasibility-report.md`. Mục tiêu: texture v0.1.0 bị nhận xét là "phẳng, giống nhiễu hạt chứ không giống giấy". Bản mới phải cho cảm giác app vẫn là app, nhưng như được in lên giấy.

Ký hiệu như báo cáo khả thi: **[XM]** đã xác minh (tài liệu hoặc đo trên máy), **[CXM]** chưa xác minh.

## 1. Các hãng làm "paper mode" thế nào

### 1.1 Xiaomi / Redmi (MIUI 12 → HyperOS)

- Paper mode nằm trong *Reading mode*. Xiaomi mô tả nó là chuyển sang màu ấm hơn và thêm texture giấy cho nền. Người dùng chỉnh được **nhiệt màu** và **độ texture**, chọn màu *Full colors / Light colors / Black and white*. [XM theo FAQ Xiaomi và Good e-Reader]
- Chuỗi trong bản beta MIUI 12 (20.6.30) mô tả "texture giấy thô áp lên mọi phần tử trên màn hình". Các bài viết sau gọi đó là hiệu ứng "grain" thêm vào pixel. Xiaomi Pad 8 có *Classic / Cycle / Texture mode* trong Reading mode. [XM theo XDA, XiaomiTime, FAQ Xiaomi Pad 8]
- **Chưa có tài liệu công khai nào** cho biết texture được ghép ở đâu (SurfaceFlinger, HWC hay một system window). Tìm trong cây thiết bị LineageOS (`IDisplayFeature.hal`) chỉ thấy các lệnh chung (`setFeature`, `sendPanelCommand`), không có texture. [CXM]
- Suy luận: phần màu (ấm, giảm bão hòa, đen trắng) gần như chắc chắn là **color matrix trong display pipeline** (như Night Light). Phần texture là một layer hệ thống. Layer hệ thống có hai lợi thế mà app thường không có: không bị giới hạn window alpha 0.8, và có thể kết hợp với color matrix để thay đổi *nội dung bên dưới* (ví dụ hạ tương phản trước khi phủ texture). [CXM]

### 1.2 TCL NXTPAPER, Honor eBook mode

- NXTPAPER là màn LCD có lớp phủ nhám khắc nano (nano-matrix lithography) để tán xạ ánh sáng, kèm chế độ phần mềm *Color Paper* (giảm tương phản, giảm bão hòa một phần) và *Ink Paper* (đen trắng). Cảm giác giấy đến **từ phần cứng** (bề mặt nhám), phần mềm chỉ chỉnh màu. [XM theo Android Central, Trusted Reviews]
- Honor eBook mode chỉnh nhiệt màu, độ sáng và thang xám để ra tông "giấy đen trắng", không có texture. [XM theo trang hỗ trợ Honor]

### 1.3 Cơ chế màu trong AOSP

`DisplayTransformManager` (system_server) nhân các ma trận 4×4 theo thứ tự level rồi gửi cho SurfaceFlinger bằng transaction `1015`: Night Display (100), white balance (125), saturation (150), grayscale (200), reduce bright colors (250), invert (300). Saturation toàn cục dùng transaction `1022`. [XM theo mã nguồn AOSP]

Các transaction này chỉ dành cho system_server và app hệ thống (SurfaceFlinger kiểm tra quyền signature `ACCESS_SURFACE_FLINGER`). App thường **không** gọi được. [CXM về đúng tên quyền]

### 1.4 Điều gì làm cho "giấy" trông như giấy

Quan sát ảnh macro giấy thật (ví dụ ambientCG Paper001, CC0) và kinh nghiệm in ấn:

1. **Răng giấy (tooth):** gồ ghề rất mịn (0.1–0.3 mm) được ánh sáng xiên chiếu vào, nên mỗi chỗ gồ có **một mặt sáng, một mặt tối**. Đây là tín hiệu quan trọng nhất. v0.1 chỉ có mặt tối nên trông như bụi.
2. **Sợi:** sợi gỗ dài 1–3 mm, sợi dó/kozo dài 5–10 mm. Sợi nổi lên mặt giấy và cũng có sáng/tối theo ánh sáng, chứ không phải nét mực đen. v0.1 vẽ sợi là nét tối nên trông như tóc hay vết xước.
3. **Formation (vón cục):** mật độ bột không đều ở tỷ lệ 2–10 mm, biên độ rất nhỏ. v0.1 dùng biên độ lớn nên trông như đá cẩm thạch bẩn.
4. **Mực ăn vào giấy:** vùng mực đậm vẫn lộ texture, vì đỉnh răng giấy nhận ít mực hơn. Đây là điều khiến app trông như *được in*. Một lớp chỉ làm tối thì **không thể** thể hiện điểm này trên chữ đen.
5. **Màu:** trắng giấy không phải trắng tuyệt đối, đen của mực cũng không phải đen tuyệt đối.

## 2. Overlay `TYPE_APPLICATION_OVERLAY` làm được gì

Ràng buộc (A1, A2, B3): chỉ có alpha compositing `out = src·a·W + dst·(1 − a·W)`, với window alpha W ≤ 0.8 để chạm xuyên. Không đọc được `dst`, nên không có overlay/soft-light/color matrix.

Một nhận xét quan trọng cho thiết kế mới: với pixel premultiplied (c, a),

```
out = c + dst·(1 − a)        (đã nhân W)
trên nền trắng: 1 − (a − c)  → làm tối một lượng a − c
trên nền đen:   c            → nâng sáng một lượng c
```

Vậy **mỗi pixel điều khiển độc lập** lượng làm tối vùng sáng và lượng nâng sáng vùng tối. Nhờ vậy ta có thể mô phỏng gần đúng một blend kiểu soft-light/overlay cho texture: bóng đổ hiện trên nền sáng, điểm sáng của phù điêu hiện (rất nhẹ) trên chữ đen và ảnh tối.

| Hiệu ứng | Hãng (display pipeline) | Overlay của ta |
|---|---|---|
| Texture có sáng/tối (emboss) | Có | **Có**: tile ARGB, (c, a) độc lập |
| Texture lộ trên vùng đen (mực ăn giấy) | Có | **Có, giới hạn**: nâng đen ≤ 5% (`MAX_LIFT`) để giữ dark mode và pin OLED |
| Ấm màu trên trắng | Có (multiply thật) | Gần đúng (B3): đúng trên trắng, đen bị nâng `C·a` |
| Giảm tương phản (đen thành "mực", trắng thành "giấy") | Có | **Có**: chính là trộn với màu giấy, `out = dst·(1−a) + P·a` |
| Giảm bão hòa | Có (ma trận saturation) | Chỉ một phần: trộn với màu giấy làm giảm chroma theo (1 − a), nhưng cũng giảm tương phản theo cùng tỉ lệ. Không giảm bão hòa riêng được |
| Đen trắng (Ink Paper) | Có | **Không** |
| Multiply màu chính xác, gamma, đường cong tông | Có | **Không** |
| Texture đổi theo cuộn trang | Không ai làm | Không (overlay đứng yên, B5) |
| Không lệ thuộc W = 0.8 | Có | Không: mọi giá trị phải chia cho W |

Gợi ý cho người dùng (đã có trong B3): để có tông ấm chính xác hoặc đen trắng, bật Night Light / Eye comfort / Bedtime grayscale của hệ thống. Các chế độ đó dùng đúng color matrix ở trên, rồi PaperScreen chỉ cần lo phần texture.

## 3. Thiết kế mới

### 3.1 Tile ARGB và chiếu sáng

1. **Height map** tuần hoàn trên tile:
   - *Tooth:* fBm 3 octave của gradient noise (Perlin) tuần hoàn. Octave mịn nhất mạnh nhất.
   - *Domes (giấy vẽ cold-press):* Worley F1 có domain warp bằng Perlin, nên trông như các ụ không đều chứ không thành tổ ong.
   - *Sợi:* Bézier bậc 2, tiết diện Gauss, rộng ≥ 1.5 px vật lý, có wrap-around qua mép. Sợi cộng vào height map, nên nổi lên và có mặt sáng/tối. Tùy preset, sợi còn mang thêm độ tối (kraft, báo) hoặc độ sáng (dó).
   - *Formation:* Perlin tần số thấp, cộng nhẹ vào height và làm tối rất ít.
2. **Albedo:** formation, độ tối của sợi, đốm tạp (giấy báo, giấy tái chế).
3. **Chiếu sáng:** pháp tuyến tính từ sai phân trung tâm có wrap (bảo đảm liền mạch). Đèn chiếu từ trên-trái, góc thấp. `shade = n·L − L_z`, trừ trung bình để 0 nghĩa là "sáng bằng mặt giấy trung bình".
4. **Ghép (đơn vị trên màn hình, rồi chia cho W):**
   - `dark = 2·intensity·(base + 0.10·max(−shade, −0.35) + albedo)`, chặn ≤ `MAX_DARKENING = 0.22`.
   - `lift = 2·intensity·0.05·max(0, shade)·(0.5 + relief)`, chặn ≤ `MAX_LIFT = 0.05`.
   - `base` là độ tối trung bình của trắng. Cần có nó để điểm sáng của phù điêu sáng hơn xung quanh ngay cả trên trang trắng.
   - `tint` của preset (kraft, dó, báo): multiply gần đúng theo B3, gộp vào cùng tile.
5. Tile ~320 dp (640–1024 px, bội số 8). Trên tablet 340 dpi tile là 680 px. Mắt không còn thấy lặp pattern ở khoảng cách đọc, vì biên độ formation nhỏ và chi tiết lớn nhất (sợi dó ~10 mm) ngắn hơn nhiều so với tile ~51 mm. Phương án ghép hai tile kích thước nguyên tố cùng nhau (B5) không cần dùng đến; nếu sau này tăng formation thì đây là bước tiếp theo.

Đã thử và loại:
- **Chỉ ALPHA_8 + emboss:** bóng đổ thì có, nhưng chữ đen và ảnh tối không có texture, nên vẫn trông như "phủ một lớp" chứ không như "in lên".
- **Sợi tối, mảnh** (như v0.1): trông như tóc hoặc vết xước trên mọi preset trừ kraft.
- **Domes Worley không warp:** giống da thuộc hoặc kim loại búa.

### 3.2 Preset

| Preset | Đặc trưng | Tint (nâng đen) |
|---|---|---|
| `PLAIN` (mặc định) | Giấy in sách mịn: tooth 0.16 mm, vài sợi mờ | Không |
| `NEWSPRINT` | Xám hơn, phù điêu mềm, sợi ngắn tối, đốm tạp | Rất nhẹ |
| `DRAWING` | Giấy vẽ cold-press: ụ ~0.7 mm, phù điêu mạnh nhất | Không |
| `KRAFT` | Nâu, sợi tối dày, formation mạnh | Có, đen thành nâu sẫm (~11% R) |
| `DO` | Giấy dó/washi: sợi dài 3–10 mm màu sáng, uốn lượn, mây formation | Nhẹ |

`intensity = 0.5` là độ đậm danh nghĩa của preset. `coarseness` nhân mọi kích thước với 0.6–1.6. `relief` chỉnh độ nổi.

### 3.3 Ảnh texture thật (CC0)

Đã đánh giá bộ Paper001–006 của ambientCG (CC0, có Color/Displacement/Normal 1K–8K). Kết luận: **không dùng** ở bản này.
- Ảnh màu đã chứa sẵn ánh sáng của lúc chụp, và Paper001 còn có chữ thấm từ mặt sau ở mép phải. Muốn dùng phải lấy kênh Displacement hoặc Normal rồi tự chiếu sáng như trên, tức là cùng pipeline với bản sinh bằng code.
- Kích thước cố định: không quy theo dpi và `coarseness` được nếu không resample, mà resample thì mất độ nét ≥ 1.5 px.
- Bản 1K-JPG nặng 2.5–8 MB mỗi bộ, so với 0 byte của bản sinh bằng code.
- Kết quả sinh bằng code (mục 4) đã cho cảm giác giấy rõ rệt.

Nếu sau này muốn thêm, hướng hợp lý nhất là: lấy *Displacement* 1K của một bộ CC0 làm height map, cắt cho tuần hoàn, cho qua đúng bước chiếu sáng 3.1. Ghi nguồn "ambientCG.com, CC0 1.0".

## 4. Kết quả trên máy thật

Lenovo TB375FC, Android 16, 2944×1840, 340 dpi, window alpha 0.8. Cùng một trang thử (chữ serif và sans 12 px, ảnh, nền trắng, gradient, nền tối #121212) mở trong Brave, chụp bằng `adb exec-out screencap -p` (chụp được cả overlay). Mỗi ảnh chỉ có **một** overlay đang chạy. Bản mới dùng mặc định: intensity 0.5, coarseness 0.5, relief 0.5, warmth 0.35, vignette 0.3. Lúc chụp bản mới, máy đang ở giao diện tối, nên lớp ấm của PaperView bị giảm còn 25% (`dimTintInDarkMode`). Ảnh v0.1.0 thì cho thấy lớp ấm chạy đủ 100% (đen bị nâng kiểu cam), tức là lúc đó máy ở giao diện sáng. Vì vậy hãy so texture, đừng so độ ấm.

Ảnh trong `docs/images/texture/`:

- `compare_text.png`, `compare_photo.png`: cắt 1:1, từ trái sang phải là không overlay, v0.1.0, v0.2 `PLAIN`.
- `presets_text.png`, `presets_dark.png`: cắt 1:1. Hàng trên là `PLAIN`, `NEWSPRINT`, `DRAWING`. Hàng dưới là `KRAFT`, `DO`, không overlay.
- `none.jpg`, `v010.jpg`, `v020_<preset>.jpg`: toàn màn hình, thu nhỏ còn 1200 px.

Số đo (trung bình RGB 0–255, vùng nền tối 1300×300 px):

| | Nền tối #121212 (18) | Độ lệch chuẩn trên nền tối |
|---|---|---|
| Không overlay | 18.0 / 18.0 / 18.0 | 0.00 |
| v0.1.0 | 25.2 / 22.0 / 17.1 | 0.27 (texture gần như không thấy) |
| v0.2 PLAIN | 20.7 / 20.0 / 18.4 | 2.02 (thấy phù điêu, đen chỉ nâng ~1%) |
| v0.2 DRAWING | 20.7 / 19.8 / 18.3 | 2.15 |
| v0.2 NEWSPRINT | 28.7 / 25.5 / 16.1 | 0.90 |
| v0.2 DO | 34.6 / 28.9 / 16.9 | 1.57 |
| v0.2 KRAFT | 45.9 / 34.6 / 15.0 | 1.65 (đen thành nâu, đúng giới hạn B3) |

Nhận xét:
- v0.1.0: trên nền trắng là những mảng mây xám với sợi mảnh như tóc. Trên nền tối thì không thấy gì.
- v0.2 `PLAIN`: mặt giấy có răng rõ, sáng/tối theo đèn trên-trái. Chữ 12 px vẫn đọc tốt. Ảnh trông như in lên giấy mờ. Trên nền tối vẫn thấy phù điêu mà đen gần như không bị nâng.
- Các preset có tint (`NEWSPRINT`, `DO`, `KRAFT`) nâng đen đúng như B3 dự đoán. Nên ghi chú điều này trong UI, hoặc giảm tint của tile khi giao diện tối (việc nên làm tiếp).
- Sinh tile 680 px mất 1.0–1.7 s trên máy này (chạy nền, có cache), so với 0.8 s của tile ALPHA_8 512 px ở v0.1.

Ảnh thử: "Hopetoun Falls" của Wikimedia Commons (CC BY-SA 3.0), chỉ dùng làm nội dung để chụp so sánh.

## 5. Nguồn

- Xiaomi FAQ, Reading mode: https://www.mi.com/global/support/faq/details/KA-171506/
- Xiaomi Pad 8 FAQ (Classic/Cycle/Texture mode): https://www.mi.com/global/support/faq/details/KA-667473/
- XDA, Xiaomi thử Paper mode trong MIUI 12: https://www.xda-developers.com/xiaomi-is-testing-an-enhanced-reading-mode-with-a-paper-color-mode/
- Good e-Reader, bật Paper reading mode: https://goodereader.com/blog/electronic-readers/how-to-enable-paper-reading-mode-in-xiaomi-phones
- XiaomiTime, "Paper Adjust V2": https://ximitime.com/xiaomis-most-interesting-hidden-feature-turn-screen-into-paper-89545/
- Android Authority, cảm nhận về paper mode: https://www.androidauthority.com/my-favorite-display-feature-for-night-use-3582060/
- Android Central, NXTPAPER: https://www.androidcentral.com/tablets/exploring-tcl-nxtpaper-display-tech
- Trusted Reviews, NXTPAPER 3.0: https://www.trustedreviews.com/explainer/what-is-nxtpaper-3-0-4406880
- Honor eBook mode: https://www.honor.com/uk/support/content/en-us15819555
- AOSP `DisplayTransformManager.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/color/DisplayTransformManager.java
- LineageOS `IDisplayFeature.hal`: https://github.com/LineageOS/android_hardware_xiaomi/blob/lineage-19.1/interfaces/xiaomi/hardware/displayfeature/1.0/IDisplayFeature.hal
- ambientCG Paper001 (CC0): https://ambientcg.com/view?id=Paper001 ; giấy phép: https://docs.ambientcg.com/license/
- Ảnh thử Hopetoun Falls (CC BY-SA 3.0): https://commons.wikimedia.org/wiki/File:Hopetoun_falls.jpg
