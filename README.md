# Photo Copilot

FYP 攝影輔助應用，協助使用者理解場景、選擇參考照片並獲得構圖及相機參數建議。

## 目前進度

已加入 Android Java／XML 的相機權限與即時預覽。先按「開啟相機」授權，優先使用後置相機；沒有後置鏡頭時使用前置鏡頭。拍攝、保存、推薦與 AI 功能尚未實作；算法及本機／遠端執行位置待實驗決定。

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
使用 AndroidX ComponentActivity、XML Views 及 CameraX 1.6.2；相機綁定畫面生命週期，切到背景時停止使用，返回時恢復。預覽保持比例，部分螢幕會留黑邊。

## 命令列建置

將 JAVA_HOME 指向相容的 JDK，並透過 Android Studio 或本機 `frontend/local.properties` 設定 SDK 路徑，然後執行：

```sh
cd frontend
./gradlew :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat`。本機 SDK 路徑不提交到 Git；不要將自己的絕對路徑寫進共用建置設定。
APK 產生於 `frontend/app/build/outputs/apk/debug/app-debug.apk`。
相機裝置整合測試放在 `frontend/app/src/androidTest/`。需要已啟動且有可用相機的測試裝置，並以全新安裝或只在測試裝置清除此 App 資料作為前置條件。測試會授予相機權限，請勿對日常使用中的 App 資料執行清除。

```sh
adb shell pm clear com.yrf3.photocopilot
./gradlew :app:connectedDebugAndroidTest
```

若第一次尚未安裝 App，第一個指令可省略。

## 相機驗證

2026-10-19：Debug APK 編譯、lint 及 API 37（Android 17）虛擬相機整合測試通過（1 個完整流程、0 失敗）。已確認真實 STREAMING、背景 IDLE、返回及 Activity 重建後恢復，並檢查預覽截圖。

自動化測試涵蓋未授權說明、授權後預覽、Activity 重建與背景返回。真機另檢查：

- 第一次開啟、允許／拒絕、重複拒絕後前往設定，以及從設定返回。
- 「僅限這次」失效後可重新請求；在設定撤銷權限後重開 App。
- 直向／橫向、切到背景／返回、鎖屏／解鎖後恢復預覽。
- 相機被其他 App 佔用、系統相機存取關閉及無鏡頭時有提示，可恢復或重試。

預覽不會保存或上傳影像，只要求 CAMERA 權限；拍攝／保存之後另行加入。
建置工具依據：[AGP 9.4](https://developer.android.com/build/releases/agp-9-4-0-release-notes)。
相機實作依據：[CameraX 預覽](https://developer.android.com/media/camera/camerax/preview)、[CameraX 版本](https://developer.android.com/jetpack/androidx/releases/camera)。

## 團隊協作

- 一個 repository 管理整個專案，一個工作項目一個分支，例如 `feat/camera-preview`。
- PR 說明變更、驗證方式與未完成部分，由另一位組員 review 後合併。
- main 保持可編譯、可啟動；提交前完成建置及 lint，畫面修改另在模擬器／真機檢查。
- 不提交個人設定、密鑰、模型權重、大量照片或研究參與者影像。

下一步：加入拍攝及保存，再接假資料推薦流程。
