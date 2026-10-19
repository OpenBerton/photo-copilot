# Photo Copilot

FYP 攝影輔助應用，協助使用者理解場景、選擇參考照片並獲得構圖及相機參數建議。

## 目前進度

已建立 Android Java／XML 起始專案，顯示簡單歡迎畫面。相機、推薦與 AI 功能尚未實作；算法及本機／遠端執行位置待實驗決定。

## 專案內容

- `frontend/`：完整 Android Studio 專案，只有一個 app 模組。
- [ARCHITECTURE.md](ARCHITECTURE.md)：功能邊界、程式放置規則與開發順序。
- `.gitignore`：排除本機設定、編譯產物及密鑰。

## 在 Android Studio 啟動

1. Clone 整個 repository，在 Android Studio 開啟 `frontend/`。
2. 使用支援 AGP 9.4 的 Android Studio（例如 Quail 4），Gradle JDK 選 Android Studio 內附 JDK。
3. 按提示安裝 Android SDK Platform 37、Build Tools 36.0.0，等待 Gradle Sync 完成。首次同步需要網絡。
4. 選擇 Android 8.0（API 26）或以上的模擬器／手機，以 app 設定按 Run。

固定版本：AGP 9.4.1、Gradle 9.6.0、compileSdk／targetSdk 37，minSdk 26。Java 原始碼相容級別是 11，與執行 Gradle 所用 JDK 不同。
目前使用 Android 平台內建 Activity 與 Views，沒有額外 UI 或 AI 套件。

## 命令列建置

將 JAVA_HOME 指向相容的 JDK，並透過 Android Studio 或本機 `frontend/local.properties` 設定 SDK 路徑，然後執行：

```sh
cd frontend
./gradlew :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`。本機 SDK 路徑不提交到 Git；不要將自己的絕對路徑寫進共用建置設定。
APK 產生於 `frontend/app/build/outputs/apk/debug/app-debug.apk`。
目前沒有業務邏輯，因此尚未加入單元測試；新增相機等功能時再加入對應測試。

## 本次驗證

- Debug APK 編譯及 Android lint 已通過（2026-10-19）。
- lint 仍提示可更新 Gradle，以及未定義 Android 12+ 資料轉移規則；目前沒有儲存使用者資料，加入照片儲存時再明確定義轉移策略。
- 現有 API 37 模擬器在一般及冷啟動後均離線，尚未完成安裝、畫面及生命週期驗證；合併前請在 Android Studio 或真機補驗。
- 建置工具相容性依據：[Android 官方 AGP 9.4 說明](https://developer.android.com/build/releases/agp-9-4-0-release-notes)。

## 團隊協作

- 一個 repository 管理整個專案，一個工作項目一個分支，例如 `feat/camera-preview`。
- PR 說明變更、驗證方式與未完成部分，由另一位組員 review 後合併。
- main 保持可編譯、可啟動；提交前完成建置及 lint，畫面修改另在模擬器／真機檢查。
- 不提交個人設定、密鑰、模型權重、大量照片或研究參與者影像。

下一步：相機權限與預覽，之後才加入拍攝及保存。
