# 架構基線

## 原則與現況

依據 2026-09-11 FYP proposal 的功能需求，採用單一 repository，按功能分組。
目前已建立 frontend Android 專案、camera 功能及 reference 示範選擇流程；其餘 package 及介面在實作相關功能時才建立。
沒有程式就不建立空資料夾；不預建 backend、models、utils、services 或多個 Gradle module。

Android 使用 Java／XML。CLIP、餘弦相似度、ML Kit、FastAPI、MySQL、Qwen／vLLM 保留為 proposal 的候選方案，並非已決定的依賴。

## 功能邊界

| 功能 | 責任 | 執行位置 |
| --- | --- | --- |
| 相機 | 權限、預覽、拍攝、保存、快照與裝置能力 | Android |
| 場景分析 | 快照轉成可由使用者修改的場景描述 | 待定 |
| 參考推薦 | 根據場景與使用者條件回傳排序照片 | 待定 |
| 參考呈現 | 照片列表、選擇、可移動及縮放的輪廓疊圖 | Android |
| 構圖建議 | 根據影像／感測資訊與選定參考回傳提示及理由 | 優先評估本機，量測後決定 |
| 參數建議 | 根據場景及相機支援範圍提供設定建議與理由 | 待定；使用者確認才套用 |
| 參考資料管理 | 參考 ID、照片、標籤、可選輪廓及檢索資料 | 儲存、快取與索引方案待定 |

後期處理與管理員 UI 暫不納入第一條可用流程，核心功能穩定後再確認範圍。

## 程式放在哪裡

Android 專案位於 `frontend/`，包含完整 Gradle wrapper 與設定。
Java package 為 `com.yrf3.photocopilot`，目前包含 MainActivity，以及 camera 內的 CameraSession、CameraLayout、PhotoStore、CompositionGrid、ZoomControl、ZoomRuler 和 RecentPhoto，reference 內的 ReferencePhoto、DemoReferences 和 ReferencePicker；其下按需要加入：

| Package | 內容 |
| --- | --- |
| `camera` | 相機控制、快照及裝置能力 |
| `scene` | 場景資料、分析介面與實作 |
| `reference` | 參考資料、推薦介面、列表與疊圖 |
| `guidance` | 構圖及參數建議，各自獨立介面 |

每個功能先平放少量類別；類別多到難以閱讀才拆子 package。
Activity 負責畫面啟動與組裝，不承擔模型、HTTP 或檢索算法。
XML 沿用 Android `res/`；測試沿用 `src/test/` 和 `src/androidTest/`，不另建根目錄 tests。

| 其他目錄 | 何時建立 |
| --- | --- |
| `backend/` | 第一個已確定需要遠端執行的功能開始實作時，加入可啟動服務及測試 |
| `experiments/` | 第一個需要保存的算法比較實驗，附執行方式與量測結果 |
| `docs/` | 文件多到根目錄難以閱讀時；目前不需要 |

模型實作放在使用它的功能／服務中。資料及權重另行儲存，記錄取得方法、版本與使用權限；有需要才選外部儲存或 Git LFS。

## 如何保留算法的替換空間

依賴方向：畫面／流程控制 → 功能介面 ← 本機或遠端實作。
開始開發某項功能時才建立 Java interface，先用明確標示的假資料實作接通流程，再換成真實算法。初期在一處集中組裝即可，不引入額外依賴注入框架。

| 能力 | 輸入 | 輸出 |
| --- | --- | --- |
| 場景分析 | 快照與請求識別 | 場景描述 |
| 參考推薦 | 快照／場景、使用者條件 | 參考 ID、照片位置、標籤、排序分數及可選輪廓 |
| 構圖建議 | 當前影像／感測資訊、可選參考 | 動作提示與理由 |
| 參數建議 | 場景及裝置支援範圍 | 可用設定建議與理由 |

這是資料責任，不是固定 Java 方法或 HTTP 格式。實作時才定義型別、錯誤及非同步完成方式。
畫面不直接依賴模型 tensor、向量維度、資料庫欄位或 HTTP response。
排序分數不等同正確率或校準過的信心百分比。

## 資料流與執行規則

```text
相機快照 → 場景分析 → 可編輯場景 → 參考推薦 → 選擇參考 → 疊圖
影像／感測資訊 + 選定參考 → 構圖建議
場景 + 裝置支援範圍 → 參數建議 → 使用者確認 → 套用
```

- 分析先由按鈕觸發；周期更新與節流頻率量測後才定。
- 影像分析、檢索及網絡操作不阻塞 Android UI 執行緒。
- 使用請求識別，避免較舊回應覆蓋新場景；離開畫面時取消或忽略未完成結果。
- 手動修改場景後重新推薦，停用自動更新直到使用者重新開啟。
- 區分載入中、空結果、成功及失敗；提供重試，推薦不可用時基本相機仍可用。
- 若採遠端分析，再加入資料傳送說明、超時、取消及圖片大小限制。API 金鑰留在服務端。

## 開發次序與完成標準

1. **架構基線（已完成）**：README、架構文件及 Git 忽略設定；清楚區分已決定與待研究項目。
2. **Android 起始專案（已完成）**：在 frontend 建立 Java／XML 專案，提交 wrapper；組員可以編譯並啟動空白畫面。
3. **基本相機（進行中）**：已加入權限、即時預覽、拍攝及相簿保存；已加入鏡頭切換、縮放、閃光燈及九宮格；持續補真機驗證。
4. **假資料推薦流程（進行中）**：已加入本機示範照片列表、選擇、換圖及取消，涵蓋空資料及圖片載入錯誤；場景操作、疊圖及非同步推薦／過期回應待實作。
5. **算法實驗與接入**：比較準確度、延遲、記憶體、網絡依賴與成本；記錄證據後決定執行位置，替換假資料而不重寫畫面流程。
6. **拍攝建議與評估**：逐一加入構圖及參數建議，驗證裝置支援和提示效用，再進行整合及用戶評估。

每一步可拆數個小 PR。技術決定先在本文件記錄日期、原因與證據，數量多時才獨立拆文件。
文件變更檢查連結、路徑及 git diff --check。Android 建置及 lint 命令見 README；後續有需要再加入 GitHub Actions。

## 已採用的技術決定

- 2026-10-19：預覽使用 CameraX 1.6.2 穩定版與 ProcessCameraProvider，選擇鏡頭後綁定 ComponentActivity 生命週期。MainActivity 管理權限／畫面，camera/CameraSession 管理預覽、拍攝與相機錯誤；沿用單一 app 模組。同一相機模組已接入 ImageCapture；ImageAnalysis 在實作場景功能時才加入。
- 預覽使用 FIT_CENTER 保留比例，以及 COMPATIBLE 模式支援之後疊圖；較舊非同步啟動結果不覆蓋已停止或已銷毀的畫面。錯誤提示在真實 STREAMING 狀態才隱藏。

- 2026-10-19：Preview 與 ImageCapture 一起綁定同一生命週期。PhotoStore 負責 MediaStore 暫存、發布與失敗清理，仍放在 camera package，不增加頂層目錄。API 29+ 使用 RELATIVE_PATH／IS_PENDING，API 26–28 使用公開圖片目錄及僅限舊版的儲存權限。用 app 自己建立的輸出串流接 CameraX，保存完成才發布，失敗刪除精確 URI。
- 保存工作使用 application context 和共用背景執行緒，畫面銷毀後仍完成保存或清理；回呼不更新已銷毀的畫面。快門只在生命週期 RESUMED、預覽串流就緒且沒有拍攝中時啟用。

- 2026-10-20：基本控制沿用 CameraSession，經 CameraX 能力查詢決定按鈕是否可用。縮放累積請求倍率並限制硬件範圍，忽略已過期操作回呼；相機重新開啟時重新套用倍率，切鏡時重設。閃光使用 ImageCapture flash mode，不開啟 torch。
- CompositionGrid 沿用 FIT_CENTER 的預覽比例，以公開 ResolutionInfo 的 crop rect 及旋轉資料計算可見影像範圍，避免跨黑邊，也不使用實驗或受限制的座標轉換 API。控件及疊圖放在現有 camera 功能內，沒有新增根目錄或服務。
- 2026-10-20：倍率與 ZoomRuler 放在預覽外、快門上方。相機控制由原先橫直向分別排列，改為以裝置本身為基準固定位置，只旋轉圖示、文字及縮圖。RecentPhoto 只背景載入本 App 最近保存的照片 URI；沿用 SeekBar 控件和系統圖片檢視器。Android 8／9 縮圖使用 AndroidX ExifInterface 修正方向。純黑背景由主題、根畫面及控制區提供，配合系統 Insets 和 FIT_CENTER，與鏡頭解析度分離，不固定預覽像素大小。
- ZoomRuler 保留 SeekBar 的鍵盤與無障礙操作，自訂直線刻度及相對拖動：中央指示固定，刻度按對數倍率移動，左右兩端以漸層淡出。同一倍增使用相同 dp 拖動距離，倍率由 CameraX 的實際範圍限制；程序同步畫面不產生新的使用者縮放請求。
- ZoomControl 在同一個固定高度區域切換原生倍率按鈕和 ZoomRuler：輕觸按鈕選倍率，水平拖動超過系統手勢門檻才攔截，取消按鈕點按並把完整拖動交給刻度。放手、取消、停用或離開畫面均回到按鈕，不改變預覽大小；無障礙展開／收合仍可使用細調。
- CameraLayout 在目前視窗及安全 Insets 內，將同一套控制項按顯示旋轉角度對齊裝置原本座標；原生 View 轉換也用於觸控及無障礙邊界。預覽內容使用獨立 FrameLayout 反向補償，讓 PreviewView、提示與九宮格保持螢幕方向，仍由 CameraX 處理感光元件方向與 FIT_CENTER；不直接旋轉或重採樣相機像素。ZoomControl 逐個旋轉按鈕文字，ZoomRuler 只旋轉刻度標籤，刻度及拖動區維持原本位置。顯示監聽器隨 View 附加／移除註冊，不依賴鎖定螢幕方向。
- CameraSession 在啟動／停止期間註冊／移除顯示監聽器，使用 [Preview.setTargetRotation](https://developer.android.com/reference/androidx/camera/core/Preview#setTargetRotation(int)) 同步明確設定的預覽方向，涵蓋不會重建 Activity 的 180° 旋轉；拍攝仍在快門時讀取目前顯示方向。這符合目前 COMPATIBLE PreviewView 的明確 target 行為。沒有以鎖定直向取代適配，因為 [Android 17 大螢幕方向限制](https://developer.android.com/about/versions/17/changes/ff-restrictions-ignored)對 target 37 不提供這類保證。
- 2026-10-20：ReferencePhoto 僅提供穩定 ID、本機圖片 URI 及無障礙描述；DemoReferences 集中提供四張示範素材。ReferencePicker 使用原生底部 Dialog、水平縮圖列表及選取／清除操作，不引入 UI 框架或額外模組。日後推薦功能提供同類資料即可接入，尚未建立沒有用途的推薦 interface。
- 縮圖以單一背景執行緒按最大邊 512px 取樣解碼，畫面僅保留小圖；關閉 Activity 後忽略未完成回呼。Bundle 保存選擇 ID 與面板開啟狀態，不保存 Bitmap。重新開啟捲到已選照片；未知 ID 或圖片失效會清除該選擇，失敗卡片停用，重新開啟可重試。這版沒有場景分析、排序、輪廓或構圖建議。

## 示範圖片來源

2026-10-20，使用內建 `image_gen` 工具的 generate 模式，一張素材一次呼叫，未使用 CLI／API fallback；`transparent_background=false`，沒有輸入參考圖片。生成結果以原始 PNG 複製到專案，未再裁切、縮放或改圖。這些素材僅供介面流程示範，不作為真實攝影評估資料或已實作的推薦結果。

| 選擇 ID | 素材 | 專案路徑 |
| --- | --- | --- |
| `waterfront` | 海邊人像 | [reference_waterfront.png](frontend/app/src/main/res/drawable-nodpi/reference_waterfront.png) |
| `street` | 街道人像 | [reference_street.png](frontend/app/src/main/res/drawable-nodpi/reference_street.png) |
| `coast` | 海岸風景 | [reference_coast.png](frontend/app/src/main/res/drawable-nodpi/reference_coast.png) |
| `mountains` | 山景 | [reference_mountains.png](frontend/app/src/main/res/drawable-nodpi/reference_mountains.png) |

以下為各次生成使用的完整最終提示詞。

### reference_waterfront.png

```text
Use case: photorealistic-natural. Asset type: one demo reference photo for an Android travel photography app. Create a single natural editorial travel photograph, vertical 3:4 composition: an adult woman in simple unbranded casual clothing standing on a quiet waterfront promenade at golden hour. Full body visible with natural hands and feet, subject on the left third, harbour water and distant generic skyline on the right, natural relaxed pose and believable skin texture. This should demonstrate balanced travel portrait composition. No text, logo, watermark, border, collage, app UI, or resemblance to a known person.
```

### reference_street.png

```text
Use case: photorealistic-natural. Asset type: one demo reference photo for an Android travel photography app. Create a single natural editorial travel photograph, vertical 3:4 composition: an adult man in simple unbranded casual travel clothing walking on a clean city pedestrian street. Full body visible, subject on the right third, subtle leading lines from paving and simple buildings, soft overcast daylight, candid relaxed pose, believable anatomy and skin texture. Generic fictional setting without legible signs. No text, logo, watermark, border, collage, app UI, or resemblance to a known person.
```

### reference_coast.png

```text
Use case: photorealistic-natural. Asset type: one demo scenery reference photo for an Android travel photography app. Create one realistic natural travel photograph, vertical 3:4 composition, of a quiet coastal headland and blue sea in clear late afternoon light. Curved rocky shoreline leads the eye from lower left towards a distant headland, horizon near the upper third, textured natural foreground and airy sky. Aesthetic but believable everyday travel photography. No people, text, logos, watermarks, borders, collage, or app UI.
```

### reference_mountains.png

```text
Use case: photorealistic-natural. Asset type: one demo scenery reference photo for an Android travel photography app. Create one realistic natural travel photograph, vertical 3:4 composition, of green mountain ridges in soft morning light. A narrow winding hiking path creates a clear leading line through the foreground, layered distant peaks and a pale sky near the upper third, realistic foliage and atmospheric depth. Aesthetic but believable travel photography. No people, buildings, text, logos, watermarks, borders, collage, or app UI.
```
