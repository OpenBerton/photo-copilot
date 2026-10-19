# Photo Copilot

FYP 攝影輔助應用，協助使用者理解場景、選擇參考照片並獲得構圖及相機參數建議。

## 目前進度

目前完成 repository 的架構基線，尚未建立可執行的 Android App。
Android 預計使用 Java 與 XML Views；算法、模型及本機／遠端執行位置待實驗決定。

## 專案內容

- `README.md`：專案入口、進度與協作方式。
- [ARCHITECTURE.md](ARCHITECTURE.md)：功能邊界、程式放置規則與開發順序。
- `.gitignore`：排除本機設定、編譯產物及密鑰。

下一步在 `frontend/` 建立 Android Studio 專案；開始實作時才新增該目錄。

## 團隊協作

- 一個 repository 管理整個專案。
- 一個工作項目一個分支，例如 `feat/camera-preview`。
- PR 說明變更、驗證方式與未完成部分，由另一位組員 review 後合併。
- App 建立後，main 保持可編譯、可啟動。
- 不提交個人設定、密鑰、模型權重、大量照片或研究參與者影像。
