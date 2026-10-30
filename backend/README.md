# 攝影參考推薦 MVP

輸入一張取景照片，以 SigLIP 2 圖像向量取得內容相關候選，再按拍法標籤選出多樣的參考圖。本版只有後端，尚未接入 Android。

## 啟動

組員先按[根目錄 README](../README.md#組員取得後端並試用) 安裝 Git LFS、clone **`feat/reference-recommendation` 分支**，並執行 `git lfs pull`。模型、200 張照片與可用索引都已隨專案提供，不必自行找圖或重新訓練。請勿以一般 ZIP 下載代替 Git LFS；缺少權重時，啟動程式會明確報錯，不會把 LFS 指標當作模型載入。

需要 [uv](https://docs.astral.sh/uv/getting-started/installation/)，使用 Python 3.13、CPU；`uv` 可自動準備 Python 與 `uv.lock` 中的套件。目前鎖定的 PyTorch 安裝包提供 Windows x64 與 Apple Silicon Mac（macOS 14 或以上）；Intel Mac、Windows ARM 未包含在這組安裝包內。首次安裝依賴需要網絡；模型與圖庫初次 clone 約 1.5 GB，另需套件及執行環境的磁碟空間。以下在 repository 根目錄執行，Windows PowerShell 與 macOS 終端使用相同指令：

```sh
uv run --project backend --locked --no-dev --python 3.13 python -m photocopilot.run --check
uv run --project backend --locked --no-dev --python 3.13 python -m photocopilot.run
```

`--check` 不啟動常駐服務，而是檢查索引與圖片資料，實際執行推薦並回報結果；成功後執行第二行啟動 API。啟動程式使用隨附的 `google/siglip2-base-patch16-224` 固定版本權重，不需要 Hugging Face 帳號、API key 或 Docker。

模型在 `backend/.cache/models/`，NumPy 索引在 `backend/.data/numpy-index/`。索引的照片路徑相對於索引位置保存，因此 clone 到不同電腦或目錄仍可使用。啟動程式會嚴格比較當前模型／前處理簽章；若索引尚未建立，或同一模型因作業系統的套件版本差異而簽章不一致，會先以隨附照片和當前模型整批重建，再開始服務，不會略過簽章校驗。這不是訓練模型；只需初次建立相容索引，之後可直接使用。壞損索引、不同模型版本、缺少照片或權重會報錯，需修復相應檔案。

依賴安裝及完整資料下載完成後，後端的模型推論、索引建立與查詢不需外部模型服務。取景圖片只在本機記憶體處理，不保存為圖庫或送往外部推論服務。

瀏覽 `http://127.0.0.1:8000/docs` 操作 API。另一個終端可執行：

```sh
curl --fail http://127.0.0.1:8000/health
curl --fail -X POST http://127.0.0.1:8000/recommend \
  -F 'image=@backend/sample_images/coffee.png' -F 'limit=6'
```

上述 `curl` 範例適用 macOS／Linux；Windows 可直接使用 `/docs`，或以 `curl.exe` 執行相同請求並將指令寫成一行。

`image` 是 JPEG、PNG 或靜態 WebP 檔案；`limit` 可省略，預設 6，允許 1–30。候選不足、去重後不足或門檻過高時，結果可少於要求數量，包括 0 張。圖庫自身圖片作為查詢時可以回傳它本身；去重作用於推薦結果中的圖片之間。

回傳格式如下，以另行啟用的咖啡示例圖庫展示；數字不是效果評估結果：

```json
{
  "demo": true,
  "candidate_count": 3,
  "returned": 1,
  "results": [{
    "id": "coffee",
    "image_path": "/images/coffee",
    "cosine_similarity": 0.8,
    "relevance_score": 0.9,
    "coverage_gain": 1.0,
    "repetition_penalty": 0.0,
    "ranking_score": 0.9354838709677419,
    "tags": {"angle": "high_angle", "shot_size": "close_up", "composition": "diagonal"},
    "reference_reasons": ["參考視角：high_angle", "參考景別：close_up", "參考構圖：diagonal"]
  }]
}
```

`image_path` 是同一 API 的相對圖片網址，不是伺服器檔案路徑。`GET /images/{id}` 僅讀取已建立索引的圖片。參考理由只引用 JSON 中提供的拍法，不會聲稱模型辨認了未提供的主體或美感。分數不是「有用的概率」。

直接啟動 `photocopilot.api:app` 而沒有索引時，`/health`、`/recommend` 回傳 503；上方共用啟動入口會先建好索引。超出檔案大小回傳 413，缺少圖片、錯誤格式或超出像素限制回傳 422。被索引的圖片已刪除時，圖片網址回傳 410。檔案預設上限 10 MiB、20 百萬像素；請求本體另限檔案上限加 64 KiB multipart 包裝，含沒有 Content-Length 的串流請求。

## 預設 Commons 圖庫

預設 `config.toml` 指向 `commons_catalog.json`。這份圖庫包含 200 張 Wikimedia Commons 的 Featured／Quality 照片：人像 67 張、風景 67 張、食物 66 張；資料擷取日期為 2026-10-29。候選照片經 AI 逐張縮圖篩選，排除畫作、掃描封面、明顯重複及不適合的題材。這些站內評選和視覺篩選是選材依據，尚未量測它們對本程式的檢索品質，也不代表已完成獨立人工美感或攝影教學評估。

照片保存在 `backend/.data/commons200/images/commons-<pageid>.jpg`，目前只保留 catalog 使用的 200 張；未入庫的候選照片、收集腳本及選材中間資料已清理。每張圖片的作者、檔案頁、授權及縮放處理記錄保存在 catalog 的 `source`，完整署名另見 [COMMONS_ATTRIBUTION.md](COMMONS_ATTRIBUTION.md)。這 200 張照片、catalog、署名和目前有效的 NumPy 索引均隨 GitHub 專案提供。`.data/` 內其他個人資料、實驗及舊世代不會自動納入 Git。

SigLIP 2 的模型說明與授權為隨附 snapshot 內的 `README.md`、`LICENSE.txt`，位置在 `.cache/models/models--google--siglip2-base-patch16-224/snapshots/75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2/`。模型權重採 Git LFS，其他個人模型快取不納入提交。更新任何已分享的照片、標籤或模型時，須一併更新相應來源記錄、完整索引世代及 `current.json`，避免組員取得不同版本的資料。

入庫是用既有 SigLIP 2 權重編碼圖片並建立向量索引，沒有訓練或微調模型。`angle`、`shot_size`、`composition` 經 AI 視覺篩選時保守標註，不確定的欄位填 `null`；未知拍法不會產生多樣性獎勵或參考理由。這些標註尚未經獨立人工覆核。

## 自訂圖庫與更新

編輯一份 JSON，並在 `config.toml` 的 `catalog_path` 指向它。圖片路徑相對於 JSON 所在目錄；配置中的路徑相對於配置檔所在目錄。

```json
{
  "schema_version": 1,
  "demo": false,
  "references": [{
    "id": "food-001",
    "image_path": "photos/food-001.jpg",
    "approved": true,
    "category": "food",
    "tags": {"angle": "俯拍", "shot_size": "特寫", "composition": null},
    "duplicate_group": null,
    "source": "照片來源及使用記錄"
  }]
}
```

- 美感篩選由圖庫提供者在入庫前完成，只有 `approved: true` 才會建立正式索引；這版沒有自動美感評分或審核介面。
- `category` 可填 `food`、`landscape`、`portrait` 或其他題材，僅作資料描述及保守去重輔助，不是自動主體分類。
- `angle`、`shot_size`、`composition` 是拍法欄位。未知填 `null`，不要猜測；相同意思使用一致文字，程式不會自動合併「俯拍」和 `overhead` 等同義標籤。
- 已知同圖、裁切版本或近似連拍，可共用 `duplicate_group`；它表示同一張／同一組近似圖片，不能用來代表整個題材。
- 更新圖片或標籤後，重新建立完整索引，再重新啟動 API。建立期間，已運行的 API 繼續使用啟動時載入的舊索引。缺圖、壞圖、重複 ID、無效向量會中止更新，保留原本可用索引。

正式資料的命令不加 `--allow-demo`：

```sh
uv run --project backend --locked --no-dev --python 3.13 python -m photocopilot.index
```

自訂配置可用 `python -m photocopilot.index --config /path/to/config.toml`；直接啟動 API 則設定 `PHOTOCOPILOT_CONFIG=/path/to/config.toml`。模型、版本、前處理或相關套件版本改變時，需重建索引，API 不會混用不一致的向量。共用啟動程式 `photocopilot.run` 會在簽章不一致時執行這一步；圖庫內容或標籤修改後，仍請主動使用上述索引命令重建。

## 檢索、去重與多樣性

1. **共用編碼**：索引和查詢均以同一 `load_image` 處理 EXIF／RGB，使用固定 revision 的官方影像前處理、SigLIP 2 視覺 pooler，輸出 float32 L2 正規化向量。儲存層再以 float64 正規化並保存，索引記錄完整編碼簽章。
2. **檢索**：API 啟動時載入 NumPy 向量矩陣，以正規化向量內積精確計算 cosine，預設最多取 200 個候選；先以原始 cosine 的 `min_similarity` 過濾，包含等於門檻者，重排前再檢查一次。候選按分數遞減排序，相同分數按 ID 排序。
3. **去重**：相同像素或同 `duplicate_group` 只保留最高相關者。近似去重需同時有接近的中央裁切／整圖 dHash 與很高的圖片間 cosine；有已知拍法或題材衝突時保留。單憑 SigLIP 分數高不會去重。這個保守方法不能涵蓋所有任意裁切和連拍，必要時用 `duplicate_group` 補充。
4. **貪婪選圖**：每一輪都選擇下列增益最高者，相同分數按穩定 ID 排序；選出一張後更新已覆蓋拍法。

```text
R = (cosine + 1) / 2
C = 新增已知 (拍法欄位, 標籤) 的數量 / 3
P = 與任一已選圖片相同的已知拍法數量之最大值 / 3
U = relevance_weight × R + coverage_weight × C − repetition_weight × P
ranking_score = (U + repetition_weight) / 三項權重總和
```

`R`、`C`、`P` 和 `ranking_score` 都是固定的 0–1 尺度，不對每次候選結果做 min-max。未知標籤不提供覆蓋獎勵。分數描述「選圖當下」的邊際效益，因此輸出列表的 `ranking_score` 不一定遞減；`cosine_similarity` 保留原始 -1–1，與 `min_similarity` 使用同一尺度。

權重、門檻、候選數量、模型及儲存位置均在 `config.toml`。初始 `min_similarity = 0.65` 只依五張示例的配對分數設定：跨題材最高約 0.612，同題材非同圖約 0.723–0.769。這不是獨立取景資料上的正式門檻，仍需重新校準。整圖 SigLIP 2 只能作主體與場景相關性的起點，不能保證忽略構圖、主體數量或背景；拍法標籤僅用於多樣性，不會自動改變內容向量。

## NumPy 索引與整批更新

儲存位置只有 `index_path = ".data/numpy-index"`，相對於配置檔。每次建立索引會產生一組新的檔案：

- `.npy` 保存 float64 L2 正規化向量，矩陣第 N 列對應照片 JSON 的第 N 筆。
- JSON 保存依序排列的照片 ID、路徑、拍法標籤、題材及去重指紋等資料，以及模型／前處理簽章、圖庫雜湊、照片數量和向量維度。
- `current.json` 保存目前世代及向量、照片資料檔案的 SHA256。完整資料寫入後才原子替換這個檔案；載入時校驗雜湊、資料數量、唯一 ID、維度及向量有效性，避免不同世代或不同順序的向量與照片混用。

請修改來源 catalog 後重建，**不要直接修改已生成的索引 JSON**。照片路徑保存為相對於索引目錄的路徑，載入時再解析到當前電腦，不記錄開發者的絕對路徑。模型簽章不同時，API 拒絕使用索引；`photocopilot.run` 會先用當前模型與前處理重新建立。索引載入不需要 pickle。

API 啟動時載入完整索引，後續搜尋不讀寫資料庫。索引可在 API 運行期間另建新世代；已運行的 API 保留啟動時的快照，必須重新啟動才採用新世代。因此這個方式適合偶爾整批更新，不提供即時增刪圖片。圖庫及模型都佔用程序記憶體；沒有 Qdrant 的單程序目錄鎖，但每個 worker 仍會分別載入模型及索引，所以啟動範例維持 `--workers 1`。影像推論仍在工作執行緒處理，模型操作仍有執行緒同步。

搜尋會逐張計算所有向量的 cosine，運算量隨圖庫大小增加；目前沒有 ANN 索引、遠端向量服務或多人負載驗證。預設僅綁定 127.0.0.1，公開部署需另外加入相應的服務設定。

### 儲存層遷移記錄

2026-10-30 已遷移至 NumPy，Qdrant 依賴與專用設定已移除。儲存位置使用 `index_path`；以原有 catalog 執行同一索引命令，即可重新編碼並建立 NumPy 索引。

遷移驗收後，按使用者要求刪除舊 Qdrant 索引與歷史備份。原始照片、`commons_catalog.json`、署名資料、模型快取及目前 NumPy 索引繼續使用。API 的輸入、回傳、圖片網址、門檻、候選數量、去重與多樣性設定維持原格式。

## 示例與假設

`demo_catalog.json` 重用 Android 的四張 AI 生成圖片（兩張人像、兩張風景），以及一張咖啡示例。所有項目維持 `approved: false`，只有明確使用 `--allow-demo` 才納入索引，API 回傳 `demo: true`。

預設設定使用 Commons 圖庫。若需重現五張示例，先把 `backend/config.toml` 複製為同目錄下的 `backend/demo-config.toml`，保留模型及其他設定，並修改以下兩項：

```toml
catalog_path = "demo_catalog.json"
index_path = ".data/numpy-demo"
```

然後使用這份獨立設定明確啟用示例；獨立資料目錄可保留正式索引：

```sh
uv run --project backend --locked --no-dev python -m photocopilot.index --config backend/demo-config.toml --allow-demo
PHOTOCOPILOT_CONFIG=backend/demo-config.toml \
  uv run --project backend --locked --no-dev uvicorn photocopilot.api:app --host 127.0.0.1 --port 8000 --workers 1
```

咖啡照片由 Rachel Michetti 拍攝，Pikolo Espresso Bar 提供；[scikit-image 官方說明](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.coffee)標示 CC0。檔案取自 scikit-image v0.25.2，SHA256 和官方資料索引相符；完整來源在 JSON 的 `source`。

這五張照片不是正式通過美感篩選的圖庫，也不足以評估食物、人像、風景的推薦準確度或拍法覆蓋。以圖庫圖片重新編碼作為查詢，只能驗證編碼／索引／API 的連接，不代表獨立取景照片的檢索品質。正式評估需另準備未入庫的查詢圖、人工相關性判斷與拍法覆蓋標註，再校準門檻和權重。

## 開發及發佈檢查

本次 NumPy 替換的測試、資料保留核對及新舊搜尋耗時見 [NUMPY_MIGRATION.md](NUMPY_MIGRATION.md)。本機預設新索引已保留現有 200 張照片的向量及簽章；日後仍按上述流程從 catalog 整批重建。

測試原始碼、pytest 和測試客戶端依賴已移除。正式安裝及啟動使用上方的 `--no-dev` 指令，僅安裝執行所需套件。Ruff 只保留在開發依賴；需要檢查程式時執行：

```sh
uv run --project backend --locked --group dev ruff check backend
uv run --project backend --locked --group dev ruff format --check backend
```

完成開發檢查後，可再執行 `uv sync --project backend --locked --no-dev` 還原成僅含執行依賴的環境。發佈前用 `/health`、`POST /recommend` 與回傳的圖片網址確認啟動、上傳及讀圖正常。

### 既有驗證記錄

以下是替換儲存層前的歷史記錄，當時使用 Qdrant，不能代替 NumPy 版本的驗收。

2026-10-29，預設 Commons 圖庫更新：

- 200 張本機照片全部可解碼，像素指紋沒有完全重複；人像 67、風景 67、食物 66。
- 實際執行離線索引 CLI，回報 `indexed: 200`、`demo: false`、`dimension: 768`。重新開啟 Qdrant，確認 200 條向量皆為有限數值且已正規化；資料庫另有一個索引中繼資料點。
- 原示例 ID `waterfront`、`street`、`coast`、`mountains`、`coffee` 全部查無資料，舊快照已刪除。示例原始圖片及 catalog 保留供示例操作，不納入使用中的資料庫；當時建立的 Qdrant 備份已在 2026-10-30 清理。
- 恢復本機 API 後，`/health` 回報 200 張且 `demo: false`；三類各一張庫內圖片、各一張未入新庫的舊示例，共六次 HTTP 推薦查詢成功，所有回傳圖片可讀取。五個舊圖片網址均回傳 404。這些為流程檢查，不是獨立檢索準確度評估。
- 當時的檢查結果摘要保留於上述記錄；一次性驗證 JSON 與選材中間資料已於後續清理中刪除。NumPy 遷移的實測報告仍保留於 [NUMPY_MIGRATION.md](NUMPY_MIGRATION.md)。

2026-10-29，移除測試原始碼與專用依賴後：

- 以 `--no-dev` 同步環境，確認沒有安裝 pytest、httpx2 或 Ruff，正式執行依賴仍完整。
- 使用獨立的索引副本啟動新的 API 程序，健康檢查回報 200 張、`demo: false`；食物、風景、人像各一張圖片的實際 HTTP 查詢及所有結果圖片讀取均成功。這是執行檢查，不是推薦準確度評估。
- 當時的 API 程序、照片及索引未變更；Ruff 在清理開發環境前檢查通過。舊 Qdrant 索引已於後續遷移驗收完成後清理。

依據：[SigLIP 2 官方模型](https://huggingface.co/google/siglip2-base-patch16-224)、[Transformers SigLIP 2](https://huggingface.co/docs/transformers/model_doc/siglip2)、[NumPy .npy 格式](https://numpy.org/doc/stable/reference/generated/numpy.lib.format.html)。
