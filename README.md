# Photo Copilot

FYP 攝影輔助 Android App，目標是根據拍攝場景及參考照片，提供構圖與相機參數建議。目前已完成基本相機及參考推薦後端；Android 可按需上傳當前取景，並在原有參考照片面板顯示推薦結果。

## 目前功能

| 功能 | 狀態 |
| --- | --- |
| 相機權限、即時預覽、拍攝及相簿保存 | 已實作 |
| 前後鏡頭切換、縮放、閃光燈、九宮格 | 已實作，依裝置能力啟用 |
| 最近拍攝照片的縮圖及開啟照片 | 已實作 |
| 當前取景推薦、參考照片的選擇、更換及清除 | 已接入 API，尚未加入疊圖 |
| 圖片相似度檢索、去重及拍法多樣性推薦 | 後端 MVP 已實作 |
| 場景分析、可編輯場景 | 待實作 |
| 參考輪廓疊圖、移動及縮放 | 待實作 |
| 構圖與相機參數建議 | 待實作 |

參考推薦需要電腦上的後端；只有按參考照片按鈕時，App 才擷取並上傳一張當前取景，不連續上傳、不存入相簿，也不觸發閃光。後端使用 SigLIP 2／NumPy 精確向量搜尋推薦參考圖，不需要帳號或 API key。前端已移除四張內建測試照片；原示例只保留在後端。

## 參考推薦後端

後端使用 Python／FastAPI、PyTorch／Transformers（SigLIP 2）與 NumPy。離線將向量保存為 `.npy`，照片資料及模型簽章保存為 JSON；API 啟動時載入記憶體，以 cosine 精確搜尋最多 200 張相關候選，過濾低相關與近似重複，再以視角、景別及構圖標籤貪婪選出預設 6 張參考圖。

圖庫適合偶爾整批更新，更新後重新啟動 API 採用新索引。索引位於 `backend/.data/numpy-index/`；原始照片、圖庫 JSON 及模型快取繼續使用，舊 Qdrant 索引與備份已移除。

啟動、JSON 圖庫格式、可調權重及示例資料限制見 [backend/README.md](backend/README.md)。預設圖庫為 200 張 Wikimedia Commons 精選／優質照片（人像 67、風景 67、食物 66），逐圖來源與授權見 [署名清單](backend/COMMONS_ATTRIBUTION.md)。正式圖庫只索引已標記 `approved: true` 的照片；原五張示例保留作獨立測試資料，不納入預設索引。新圖庫的 AI 視覺篩選和拍法標註尚未經獨立人工覆核或推薦品質評估。

### 組員取得後端並試用

這份成果在 **`feat/reference-recommendation` 分支**。模型權重使用 Git LFS 保存；200 張照片、署名、圖庫 JSON 和可用的 NumPy 索引也一併提供，不需要自行收集照片或先訓練模型。

目前鎖定套件提供 Windows x64 及 Apple Silicon Mac（macOS 14 或以上）的 Python 3.13 安裝包。先安裝 [Git LFS](https://git-lfs.com/) 及 [uv](https://docs.astral.sh/uv/getting-started/installation/)，再開啟 PowerShell 或終端執行。首次下載約 1.5 GB 的模型與圖庫，安裝 Python／套件另需網絡和磁碟空間；完成時間取決於網速。Intel Mac、Windows ARM 的安裝不在這組套件的已提供範圍內。

```sh
git lfs install
git clone --branch feat/reference-recommendation --single-branch https://github.com/OpenBerton/photo-copilot.git
cd photo-copilot
git lfs pull
uv run --project backend --locked --no-dev --python 3.13 python -m photocopilot.run --check
uv run --project backend --locked --no-dev --python 3.13 python -m photocopilot.run
```

`--check` 會檢查隨附資料，並用真實模型進行推薦；成功後執行最後一行啟動 API，再開啟 <http://127.0.0.1:8000/docs> 上傳照片試用。`uv` 會準備 Python 3.13 與鎖定的依賴，無須 Docker 或手動啟用虛擬環境。模型使用隨附的固定版本，不需再次從 Hugging Face 下載。若同一模型在當前系統的前處理／套件簽章與隨附索引不同，啟動程式會先從這 200 張照片重建相容索引，首次啟動因此較久；仍會保留嚴格簽章與資料完整性檢查。Windows x64 及 macOS 15.5 Apple Silicon 的 API 實測均已通過；測試環境與 Mac 下載限制見[實際驗證記錄](backend/README.md#團隊共用驗證)。

已經 clone 的組員先切換到上述分支並更新，再執行 `git lfs pull` 和同一組啟動指令。請使用 Git clone；GitHub 的一般 ZIP 下載可能只包含模型的 LFS 指標，不能代替完整權重。Android App 仍須在 Android Studio 建置；模擬器連線方式見下方說明。

## 開始使用

### 開發環境

| 項目 | 專案設定 |
| --- | --- |
| 開發工具 | 支援 Android Gradle Plugin（AGP）9.4.1 的 Android Studio |
| Gradle | 9.6.0，使用專案內附的 Gradle wrapper |
| Android SDK | compileSdk／targetSdk 37 |
| 最低支援版本 | Android 8.0（API 26） |
| 程式與畫面 | Java 11 原始碼、XML Views |
| 相機 | CameraX 1.6.2 |

專案以 `frontend/gradle/gradle-daemon-jvm.properties` 指定 Gradle daemon 使用 Java 25；本機沒有對應 JDK 時，Gradle 可能需要下載。Java 11 是原始碼相容級別，與執行 Gradle 的 JDK 版本不同。

### 在 Android Studio 啟動

1. 取得整個 repository，在 Android Studio 開啟 `frontend/`。
2. 設定 Gradle JDK，依提示安裝 Android SDK Platform 37 及所需 Build Tools，完成 Gradle Sync。首次同步需要網絡下載依賴。
3. 選擇 Android 8.0 或以上、有可用相機的手機或模擬器，以 `app` 設定按 Run。
4. 首次開啟允許系統的相機權限，即可進入預覽。

本機 SDK 路徑由 Android Studio 設定，或寫入 `frontend/local.properties`，不提交到 Git。

### Android 模擬器連接後端

1. 在 repository 根目錄啟動後端，保持此終端運行：

   ```sh
   uv run --project backend --locked --no-dev --python 3.13 python -m photocopilot.run
   ```

2. 在 Android Studio 執行 debug App。預設 API 網址為 `http://10.0.2.2:8000/`，這是 Android 模擬器連到電腦 `127.0.0.1:8000` 的位址；不必更改後端的預設綁定位址。
3. 允許鏡頭權限，預覽出現後按頂部參考照片圖示。後端傳回圖片會顯示在原有橫向照片格，點選即可返回相機。

API 網址由 Gradle property `photocopilot.apiBaseUrl` 設定，編入 `BuildConfig.API_BASE_URL`；如需更改，可在建置時加上 `-Pphotocopilot.apiBaseUrl=https://your-host/`。只有 debug 允許 HTTP；目前預設位址只適用 Android 模擬器，真機需另設定可連線的後端位址。

Android 17 首次連接電腦時，按參考照片按鈕會顯示系統的附近裝置權限提示。允許後才開始取景推薦；拒絕仍可使用基本相機。若系統不再詢問，再按同一按鈕會開啟 App 設定。這是[本機網絡權限](https://developer.android.com/privacy-and-security/local-network-permission)，不是要求讀取相簿。

### 基本操作

- **拍照**：按圓形快門，JPEG 會存入相簿的 `Pictures/PhotoCopilot`。保存期間會顯示進度並停用快門，完成後更新左側縮圖；點縮圖可交由手機圖片檢視器開啟。
- **切換鏡頭與閃光**：快門右側切換前後鏡頭；頂部閃光依序切換關閉、自動、開啟。裝置不支援的控制會停用。
- **縮放**：點按常用倍率或雙指縮放；在倍率按鈕區左右拖動可使用細調刻度，向左放大、向右縮小，放手後恢復按鈕。長按上方倍率可重設為 1×。
- **九宮格**：由頂部圖示切換，只顯示在預覽的實際影像範圍，不寫入照片。
- **參考照片**：由頂部照片圖示擷取當前取景，向後端取得最多 6 張結果，在原有照片格內顯示。每次重新按入口都會重新查詢；載入中顯示進度，沒有結果或失敗時可重試。選圖後返回相機，可換圖或清除選擇，目前不影響預覽及拍攝結果。

相機控制項以裝置原本方向固定排列；旋轉手機時，圖示、文字及縮圖會轉向螢幕方向。預覽保持比例，可能留有黑邊。

### 權限與資料

- Android 10 以上只要求相機權限；Android 8／9 在第一次拍照時另要求儲存權限。
- 拒絕相機權限後，可點「開啟相機」重試；系統不再詢問時，改由「前往設定」授權。
- 只有按快門才保存照片；App 不讀取其他相簿照片。最近照片只記錄本 App 成功保存的照片 URI。
- 參考推薦會把一張縮小的取景 JPEG 傳到所設定的後端；後端只在記憶體處理查詢圖片。關閉面板或切到背景會取消未完成請求。
- 參考結果的 ID、圖片網址及所選項目可在畫面重建時恢復，但不會作為跨次啟動的永久偏好設定。

## 專案結構

```text
photo-copilot/
├── README.md                 # 啟動、使用及開發檢查
├── ARCHITECTURE.md           # 模組責任、資料流程及後續擴充
├── backend/                 # 推薦 API、離線索引及配置
└── frontend/                # Android Studio 專案
    ├── app/
    │   └── src/
    │       └── main/
    │           ├── java/com/yrf3/photocopilot/
    │           │   ├── MainActivity.java
    │           │   ├── camera/
    │           │   └── reference/
    │           └── res/     # 版面、圖示及字串
    ├── gradle/wrapper/
    └── gradlew
```

修改功能前先看 [ARCHITECTURE.md](ARCHITECTURE.md)，確認程式所屬模組與責任。

## 建置與檢查

命令列需先設定相容的 `JAVA_HOME` 及 Android SDK 路徑。以下指令在 repository 根目錄開始執行；Windows 將 `./gradlew` 改為 `gradlew.bat`。

### 建置與 lint

```sh
cd frontend
./gradlew :app:assembleDebug :app:lintDebug
```

APK 位於 `frontend/app/build/outputs/apk/debug/app-debug.apk`，lint 報告位於 `frontend/app/build/reports/lint-results-debug.html`。

### 發佈前檢查

Android 測試原始碼與專用依賴已移除。發佈版不允許本機開發用的 HTTP，須以 HTTPS 後端建置，例如 `./gradlew :app:assembleRelease -Pphotocopilot.apiBaseUrl=https://your-host/`；這會產生未簽署的 release APK，正式發佈仍須使用自己的簽署設定。

在真機確認相機權限、拍照與保存、切換鏡頭、縮放、旋轉及背景返回。實體鏡頭、閃光、各廠商設定與圖片檢視器，以及 Android 8／9 儲存權限流程需要對應裝置確認。

## Android 接入驗證

2026-10-31，在 Android 17／API 37 模擬器完成以下檢查：

- Debug 建置與 lint 通過，無錯誤；6 項提醒涉及既有版面、備份設定及工具版本。另以 HTTPS API 網址完成 release 建置，未驗證公開部署。
- 21 組真實 HTTP 客戶端測試及 8 組參考面板測試通過，涵蓋 6／2／0 張結果、JPEG 縮放、錯誤重試、照片選取及取消後忽略舊回應。驗證後移除暫時測試程式與 APK，沒有新增正式依賴。
- 實際點按入口，完成系統本機網絡授權、相機取景擷取及真實模型 API 請求。預設虛擬房間沒有相近圖庫照片，正確顯示空結果。拒絕權限時相機仍可使用且沒有上傳；永久拒絕可前往設定，授權返回後繼續推薦。
- 另以 `commons-38582.jpg` 作受控快照，經正式面板及真實模型 API 回傳 6 張照片，全部成功下載、顯示並可選取。這項檢查驗證真實結果顯示，與虛擬相機的房間畫面檢查分開記錄。

尚未在實體手機、其他 Android 版本及不同網絡環境驗證；這些流程檢查不代表推薦品質評估。

## 團隊協作

- 一個工作項目使用一個分支，例如 `feat/reference-overlay`；PR 說明變更、驗證結果及未完成部分，由另一位組員 review 後合併。
- `main` 保持可編譯、可啟動。Android 程式變更完成建置與 lint；相機或介面修改另在裝置確認操作。後端變更執行 Ruff 檢查，並確認 API 上傳和圖片讀取。
- 功能或啟動方式改變時，同步更新這兩份文件。
- 不提交個人 SDK 路徑、IDE 設定、密鑰、編譯產物或研究參與者影像。專案指定的 SigLIP 2 權重透過 Git LFS 保存，公開授權的圖庫照片與有效索引一併版本管理；其他模型快取及本機產物仍不提交。
