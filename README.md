# KazeMail

ローカル完結・非破壊（Non-Destructive）設計の Android メールクライアントです。Kotlin（Jetpack Compose / Material 3 / Navigation 3 / Room 3 + SQLCipher / WorkManager）と、Rust（`kazemail-core`）+ Mozilla UniFFI コアで構成されています。

## 主な特徴

- **非破壊ローカルアーカイブ・ローカル検索**: サーバー上のメールを削除・変更せず端末内でアーカイブ／ローカル自動仕分け・FTS5 全文検索を実行
- **マルチアカウント・OAuth2 / アプリパスワード対応**: Gmail / Microsoft (Outlook / 個人アカウント) / 一般 IMAP・SMTP に対応
- **セキュア設計**: Android Keystore (AES-256-GCM) と SQLCipher 暗号化 DB で認証情報・API キーを保護
- **HTML / プレーンテキスト両対応コンポーザー**: アカウントごとの署名設定、HTML ↔ テキスト非破壊切替、安全なサンドボックス HTML プレビュー
- **AI 執筆アシスタント (BYOK)**: Gemini / OpenAI / Anthropic / OpenAI 互換 API に対応（逆質問確認・本文からの件名生成・件名 AI 別枠設定・プレーン/HTML 出力選択）

## ディレクトリ構成

- `app/` — Android アプリ本体（Jetpack Compose UI・ViewModel・Room 3 DB・WorkManager・AI クライアント）
- `rust/kazemail-core/` — Rust IMAP / MIME / zstd HTML 圧縮・UniFFI コア
- `app/src/main/jniLibs/` — 各 ABI 用ビルド済みネイティブライブラリ (`libkazemail_core.so`)

## ローカルビルド手順

1. **必須環境**: JDK 17・Android SDK（`compileSdk = 36`, `minSdk = 26`）
2. **OAuth2 設定（任意）**:
   ```bash
   cp oauth.properties.example oauth.properties
   ```
   `oauth.properties` は `.gitignore` 対象のため Git 管理されません（未設定時はアプリ内の入力欄から指定可能）。
3. **テスト・ビルド実行**:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
   ```
   - macOS / Linux では初回のみ `chmod +x gradlew` が必要な場合があります。
   - Rust コアのユニットテストは `rust/` 配下で実行します:
     ```bash
     cargo test --manifest-path rust/Cargo.toml
     ```

## GitHub 開発フロー

- 機能追加・不具合修正は `main` から `feat/...` や `fix/...` ブランチを作成して実装し、Pull Request でレビュー・CI チェック（`.github/workflows/android-ci.yml`）を通してマージします。
- 変更完了後はテスト・デバッグ APK ビルドを確認し、Google Drive 配布用フォルダ（および必要に応じ GitHub Releases）へ出力します。
