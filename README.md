# 纸镜 PaperLens

> 把论文读成人话 —— 一个跑在手机上的本地论文阅读 App。

「纸镜」是一个无需注册、完全本地的 arXiv 论文阅读器：每天为你整理 Hugging Face
Daily Papers 精选榜，按关键词订阅 arXiv 最新论文，并用你自己的 OpenAI 兼容接口
把论文一键「翻译」成三層大白话 —— **故事 / 细节 / 第一性原理**。

## 下载安装

前往 [**Releases**](https://github.com/Everett406/PaperLens/releases) 下载最新
`PaperLens-vX.Y.Z.apk` 直接安装（正式签名版，非 debug）。

- 系统要求：Android 12（API 31）及以上
- 校验：每个 Release 附带 `SHA256SUMS.txt`

## 功能一览

- **今日**：精选（HF Daily Papers 当日榜，当日为空自动回退前一天）+ 订阅
  （多关键词 arXiv 结果合并、去重、按时间排序），下拉刷新，断网可浏览缓存
- **书架**：稍后读 / 已读 状态管理、一句话笔记、长按呼出操作面板
- **AI 三层阅读**：接入任意 OpenAI 兼容接口（OpenAI / DeepSeek / Kimi…），
  流式输出、按 (arxiv_id, layer) 本地缓存、支持重新生成
- **搜索**：500ms 防抖实时搜索 arXiv（标题/摘要），最近 10 条历史本地保存
- **全离线优先**：所有列表先渲染 Room 缓存，网络成功后 merge；断网冷启动全 App 可浏览

## 设计与动效

- Miuix（HyperOS 风格组件库）+ Monet 动态取色：8 个种子色即时换肤，深浅色三态
- 亚克力质感只出现在三处（Haze 实现，blur 20dp + surface α0.72）：
  底部悬浮胶囊 Tab、今日页顶栏、详情页吸顶操作栏 —— 列表 item 层永不模糊，保帧率
- 超椭圆（squircle）圆角：大卡片 24dp 档、小组件 16dp 档
- 全局弹簧物理：分段指示器、底栏滑隐、页面转场、卡片按压 0.96 回弹、
  收藏书签弹性缩放微旋转、列表→详情 SharedTransition 共享元素 morph

## 技术栈

Kotlin · Jetpack Compose（AGP 9 内置 Kotlin）· MVVM + Repository · Room ·
DataStore · Retrofit/OkHttp + kotlinx.serialization · arXiv Atom 用 XmlPullParser
手写解析 · Coil · Miuix · Haze · 手动依赖注入（无 DI 框架）

版本锁定明细见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

## 自己构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 37）
./gradlew :app:assembleRelease          # 无签名配置时产出未签名 APK
./gradlew :app:assembleDebug            # 本地调试
```

## 发版流程（版本管理）

语义化版本 vX.Y.Z，打 tag 即自动构建并发布 GitHub Release：

```bash
git tag v1.0.1
git push origin v1.0.1
```

- versionName 取自 tag；versionCode = major×1000000 + minor×1000 + patch（单调递增）
- Actions 会用仓库 Secrets 中的密钥签名，并执行 `apksigner verify` 后才发 Release
- 也可在 Actions 页面手动 `Run workflow`（仅构建产物，不发布）

## 签名密钥（重要）

正式签名密钥 `.jks` 通过 GitHub Secrets 注入 CI，**没有入库**；本地构建请自备
`keystore/keystore.properties` 与 `keystore/*.jks`（格式见 release.yml）。

⚠️ 密钥一旦用于发布，请务必妥善备份；丢失将无法向老用户推送升级安装
（换签名 = 卸载重装）。

## License

[MIT](LICENSE)
