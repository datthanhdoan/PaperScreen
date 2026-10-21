# Ký APK bằng keystore cố định

Mỗi runner GitHub sinh một debug keystore mới, nên APK từ hai lần build khác nhau có chữ ký khác nhau và không cài đè được (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Vì vậy mọi bản build (debug lẫn release) được ký bằng **một keystore cố định** lưu trong GitHub Secrets. Thiếu secret thì workflow tự fallback về debug key và in cảnh báo.

## Các secret cần có

| Secret | Nội dung |
|---|---|
| `KEYSTORE_B64` | File keystore PKCS12, mã hóa base64 trên một dòng |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore, tối thiểu 12 ký tự |
| `KEY_ALIAS` | Alias của khóa, ví dụ `paperscreen` |
| `KEY_PASSWORD` | **Đặt giống hệt `KEYSTORE_PASSWORD`**. PKCS12 chỉ có một mật khẩu. |

Tạo secret ở: repo → Settings → Secrets and variables → Actions → New repository secret.

## Tạo keystore không cần máy local

Workflow `Generate signing keystore` (`.github/workflows/keystore.yml`) chạy `keytool` trên runner. Workflow `workflow_dispatch` chỉ chạy được khi file đã nằm trên nhánh mặc định, nên làm việc này **sau khi merge PR v1**.

1. Tạo ba secret `KEYSTORE_PASSWORD`, `KEY_PASSWORD` (cùng giá trị) và `KEY_ALIAS`. Nên dùng mật khẩu ngẫu nhiên dài, lưu vào trình quản lý mật khẩu. Mất mật khẩu hoặc keystore thì không phát hành bản cập nhật cài đè được nữa.
2. Chọn một trong hai cách đưa keystore vào `KEYSTORE_B64`:
   - **Tự động (khuyên dùng):** tạo một fine-grained personal access token chỉ cho repo này, quyền *Secrets: Read and write*, hạn ngắn (1 ngày). Lưu thành secret `SECRETS_ADMIN_TOKEN`. Workflow sẽ tự ghi `KEYSTORE_B64`, keystore không rời khỏi runner. Xong thì xóa secret `SECRETS_ADMIN_TOKEN` và thu hồi token.
   - **Thủ công:** không tạo `SECRETS_ADMIN_TOKEN`. Workflow upload artifact `keystore-b64` (giữ 1 ngày). Tải về, dán nội dung `KEYSTORE_B64.txt` vào secret `KEYSTORE_B64`, rồi **xóa ngay workflow run đó** (repo public nên artifact có thể bị người khác tải; keystore vẫn được mật khẩu bảo vệ nhưng không nên để lộ).
3. Vào tab Actions → `Generate signing keystore` → Run workflow. Workflow từ chối ghi đè khi `KEYSTORE_B64` đã tồn tại, trừ khi chọn `overwrite` (đổi khóa thì các bản đã cài sẽ không cập nhật được nữa).
4. Chạy lại workflow `Build`. Bước *Print signing certificate* in SHA-256 của chứng chỉ; hai lần build liên tiếp phải ra cùng một giá trị (rủi ro R11).

## Ghi chú

- Bản debug có `applicationId` hậu tố `.debug`, nên cài song song được với bản release.
- `versionCode` lấy từ `github.run_number` của từng workflow. Workflow `Release` có bộ đếm riêng, tăng dần theo mỗi tag.
- PR từ fork không đọc được secrets, nên APK của chúng luôn ký bằng debug key tạm.
- Phát hành: tạo tag `vX.Y.Z` và push, workflow `Release` build `assembleRelease`, ký, rồi tạo GitHub Release kèm `PaperScreen-vX.Y.Z.apk`.
