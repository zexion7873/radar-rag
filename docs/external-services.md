# 外部服務

radar-rag 會呼叫、部署在上面，或建置與發佈時要拉取的每一項外部服務。各服務在執行時拿到哪些資料，見 [PRIVACY.md](../PRIVACY.md)；本檔只列清單。

## 執行時

| 服務 | 用途 | 何時呼叫 |
|---|---|---|
| Notion API | `/sync` 讀取 Trending 與 Blog Archive 兩個資料來源 | 每次同步 |
| Anthropic API | `/ask` 產生回答 | 只有 `/ask`；`/search` 從不呼叫 |
| Cloudflare Turnstile | `/ask` 呼叫模型前，先用 siteverify 檢查頁面送來的機器人驗證 token | 設了 `TURNSTILE_SECRET` 時（`prod` profile 必須設定） |
| Langfuse Cloud | 每個請求的 OTLP trace；預設 JP 區，可用 `LANGFUSE_BASE_URL` 改 | 兩把 Langfuse key 都設定時；否則什麼都不送 |
| Postgres + pgvector | 向量資料庫 | 一律需要：公開 demo 用 Neon，本機用 compose 的容器 |

Embedding 由 ONNX 模型在程式內計算，不呼叫任何 embedding API。

服務本身從不呼叫 github-radar-ui（`whyisthistrending.vercel.app`）。它是 `prod` profile 唯一允許的 CORS 來源，`GET /` 也會轉址到它的 `/ask` 頁。

## 公開 demo 的部署

| 服務 | 用途 |
|---|---|
| Google Cloud Run | 執行服務 |
| Google Secret Manager | 存放所有憑證；`deploy.yml` 只寫密鑰名稱，從不寫值 |
| Google Artifact Registry | 存放 `deploy.yml` 建置的映像 |
| Google Workload Identity Federation | 讓 `main` 上的 `deploy.yml` 不靠存放的金鑰就能登入 Google Cloud |
| Neon | 託管的 Postgres |

## 建置

| 服務 | 用途 |
|---|---|
| Hugging Face | `scripts/fetch-models.sh` 下載固定版本的 embedding 模型，並驗證 sha256 |
| Docker Hub | 只拉取基底映像：`maven`、`eclipse-temurin`、`pgvector/pgvector`；本專案的映像只推到 Artifact Registry |
| Maven Central | Java 相依套件 |
| PyPI | eval harness 的相依套件，透過 `uv` 安裝 |

## CI

| 服務 | 用途 |
|---|---|
| GitHub Actions | 所有 workflow，使用來自 `actions`、`docker`、`astral-sh`、`google-github-actions`、`anthropics` 的 action |
| Anthropic API | `eval-llm`（付費；只在 owner 加標籤或手動觸發時執行）與 Claude Code workflow |
| Langfuse Cloud | 設了 key 時，`eval-llm` 會把每次執行鏡像成一個 Langfuse experiment |
