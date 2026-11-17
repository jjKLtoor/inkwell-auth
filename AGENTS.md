# Inkwell Auth · AI 开发约束（AGENTS.md）

> 项目：<https://github.com/jjKLtoor/inkwell-auth> ｜上游：<https://github.com/radiumCN/inkwell>
>
> 本文件只约束边界、协作、质量和交付。业务及实现以 [`docs/开发指南.md`](docs/开发指南.md) 为准；动态参考数据与测试口径以 [`docs/测试来源.md`](docs/测试来源.md) 为准。**不得把拟建功能描述为已实现，不得把模拟测试描述为真实登录验证。**

## 一、每轮工作入口

1. 先读本文件、上游 `CLAUDE.md`、`README.md`、相关源码和测试，再读与当前任务相关的开发指南与测试来源文档；遇到冲突先核实仓库现状，原则上保留更严格的安全约束。
2. 任务开始前列明：目标、现有能力、计划修改范围、验收条件。只实现本轮任务，不顺带大规模重构。
3. 使用中文编写必要代码注释，解释业务意图、并发、安全和兼容性原因；命名与格式遵循项目现有风格。
4. 结束前更新相关文档、记录测试命令及真实结果、说明未完成项。未经明确授权，不推送远程、不发布 Release、不向上游提交 PR。
5. 必须保护用户数据：不因数据库迁移、登录退出、来源更新或上游合并清空书架、阅读记录和来源配置。

## 二、项目目标与边界

**唯一核心目标**：为用户自主导入的 Inkwell / Legado 兼容「来源」增加通用登录认证能力，覆盖网页登录、部分规则自定义登录、认证数据持久化、自动请求认证及退出登录。

- 保留上游现有的 `:app`、`:core`、`:reader`、`:baselineprofile` 职责，尽量新增独立 `:source-auth` 模块；不重写阅读器、书架、来源解析和网络引擎。
- 来源名称、规则和更新内容属于用户自主管理的数据；**不把第三方来源合集内置 APK**，不提供账号/密码/Token/付费正文。
- 不做起点、番茄等专用客户端，不实现绕过 DRM、付费、验证码、风控、证书检查或第三方平台访问限制的代码。
- 不能把“提供标准网页登录”和“平台允许第三方客户端接入”混为一谈。真实站点验证须遵守其服务条款，并有可使用的测试权限。
- 外部书源脚本视作不可信代码。已有的私网访问保护、脚本沙箱、请求超时、取消与域名限制不可减弱。

## 三、技术与架构硬规则

1. 遵循上游 Kotlin、Jetpack Compose、**Miuix**、Koin、OkHttp、Room、Rhino、Gradle Kotlin DSL 和 JDK 21；UI 不另起 Material 3 风格。用户可见文案统一称「来源」，不用「书源」。
2. `:core` 保持纯 JVM，不得导入 Android WebView、Context、Keystore 等 Android API；` :source-auth`（无前导空格的正式 Gradle 模块名为 `:source-auth`）如需 WebView 应采用 Android Library，并由 `:app` 完成 UI 与 Koin 集成。依赖不得成环。
3. 优先复用 `BookSourceSchema`、`SourceHttpClient`、`BookSourceEngine`、`RuleEvaluator`、`SourceBridge`、`EngineJsHttp`、`WebViewPageRenderer`、`WebViewNetworkGuard`。只在必要的稳定边界增加接口；不要分散修改每个抓取函数。
4. 已核实的基础能力：`loginUrl` / `loginUi` 字段可解析，`SourceBridge` 有部分登录数据 API，`SourceHttpClient` 使用**内存** `MemoryCookieJar`。这些不等于已有登录界面或安全持久化。
5. `loginUrl` 可能是 JS 或动态规则，**不能一概当网页 URL**；`loginUi` 可能需要解释表单配置。先分析字段语义，再选择支持策略；未知类型明确标为不支持。
6. 同一域名的多来源、WebView CookieManager 的全局属性、OkHttp CookieJar 的共享行为必须作为隔离风险处理；不得承诺未经验证的“每来源独立账号”。
7. 密钥通过 Android Keystore 保护，敏感会话只存本地；不进入普通 JSON、日志、崩溃报告、WebDAV 备份、Git 或 CI 产物。不默认存储明文密码。
8. 凭据只按经过验证的来源/域名作用域发送；处理重定向和子域时遵守 Cookie Domain、Path、Secure、Expires 与 host-only 规则。登录态的共享与隔离必须有测试。
9. 合法的登录检测结果分清「已确认登录 / 未登录 / 登录中 / 过期 / 未知 / 错误」；Cookie 存在或 HTTP 200 不能证明已登录。
10. 不因 UI 需要而修改阅读器排版逻辑；认证失败不得阻断无登录要求的来源。

### 新增模块的 UI 一致性（必须遵守）

- 我们补充的登录认证及后续新增模块，**UI 设计风格、布局、组件、主题、配色、字体、字号、字重、行高、间距、圆角和动效必须与主分支现有同类页面保持一致**。先对照主分支同类页面和 `CLAUDE.md` 的「UI 统一层」，不为新增模块另建一套视觉标准。
- 优先复用 `AppScaffold`、`AppTopBar` / `AppToolbar`、`SettingGroup` / `SettingRow`、`AppAlertDialog` 和通用按钮；尺寸、文字层级、颜色和动效使用现有 `Dimens`、主题文字样式及统一令牌，不在模块内另写默认字体或硬编码字号、颜色、尺寸。
- 保持跟随应用主题、日夜模式和系统字体缩放；不得通过固定 `fontScale`、强制浅色、缩小字号或堆叠说明文字掩盖布局问题。必要的规则原文等宽字体也应沿用项目已有同类展示方式。
- 新增页面须明确主次操作、加载、空态、错误与受限状态；长规则和大段文本按需展示，不因进入新增页面而在主线程重复解析或一次性排版全文，影响原有页面转场。
- 登录入口与网页资源分开：规则明确声明的网页可单选，不能把 CSS、脚本、图片等资源域名当作登录入口。按本轮用户要求，普通公网 HTTPS 网页、跳转与资源自动加载，不要求逐站确认或点击加载；保留私网和证书保护，阅读引擎认证作用域不随网页访问范围扩大。
- WebView 的应用外框、状态提示、操作栏和确认弹窗遵循上述统一规范；网站自身表单与验证码保留站点行为，优先检查移动视口和缺失样式资源，不为统一外观强行改写网站登录流程或伪装浏览器环境。
- 交付 UI 改动时对照现有页面核验布局和字号，并按改动检查日夜主题、系统大字号及窄屏显示；提供实际截图或设备证据，未验证项明确记录，不把设计推断写成已通过。

## 四、动态测试来源（禁止固定版本）

1. 参考仓库：<https://github.com/luanmuc/legado-book-sources>；发行页：<https://github.com/luanmuc/legado-book-sources/releases>。
2. **日常测试先请求 GitHub 最新正式 Release 元数据**：`https://api.github.com/repos/luanmuc/legado-book-sources/releases/latest`；从 `assets[].name` 查找 `books.json`、`clean_rules.json`，使用实际返回的 `browser_download_url`，禁止拼接固定 Tag 下载地址。
3. 必须校验 API 响应、资产名称、下载状态、大小、SHA-256、JSON 结构；保存 `tag_name`、`published_at`、哈希和测试时间，便于复现。
4. 如最新 Release 无所需资产、不可下载或 JSON 结构异常，不覆盖缓存；报告失败并继续使用最近**已经验证**的测试快照。缓存仅供开发测试，不能默默变成用户的生产来源。
5. 每次更新生成差异报告（新增、修改、移除、字段变化），只分析是否需扩展兼容，**不得自动覆盖、删除、禁用用户来源或清理其登录凭据**。
6. “大厂正版”仅为第三方合集中的分类标签；数量、顺序、名称与是否登录以**当次 JSON 实际字段**为准，绝不硬编码。分类不意味着网站授予第三方客户端授权。
7. 网络真实测试受授权、频率和站点协议约束；默认使用本地夹具与 MockWebServer，未经授权不自动批量登录、抓取正文或尝试风控绕过。
8. 兼容性数据更新属于**开发验证流程**，不额外开发用户端自动订阅更新功能，除非用户另行提出。

## 五、Git、测试与交付

- 本地打包和发布版本必须与用户指定的 fork 仓库基线版本一致（当前为 `0.5.9`）；认证模块修改、调试修复或重新打包均不得自行递增版本号。构建差异通过文件名、提交号和交付说明区分；只有用户明确要求升级版本时才修改版本号。安装由用户自行处理，不主动安装、降级或卸载手机应用。
- 本仓库提交身份固定为 `jjKLtoor <131401293+jjKLtoor@users.noreply.github.com>`。仅使用 `git config --local user.name jjKLtoor` 与 `git config --local user.email 131401293+jjKLtoor@users.noreply.github.com`，不得修改全局 Git 身份。每次提交前核对有效的 Author / Committer；修改已推送提交的身份必须有用户明确授权，不自动改写远端历史。
- `origin`：`jjKLtoor/inkwell-auth`；`upstream`：`radiumCN/inkwell`。`main` 为稳定集成分支，`feature/source-auth` 为功能开发分支（存在与否先检查）。上游通过 Fetch + Merge / PR 同步，**不使用 `reset --hard` 覆盖自定义代码**。
- 每次上游更新必须验证模块编译、Koin、HTTP、WebView、Room 迁移与原有阅读功能，并核查变更是否触及认证扩展点。
- 上游已有测试基线：`./gradlew :core:test :reader:test :app:testDebugUnitTest` 与 `./gradlew assembleDebug`；新增模块按实际 Gradle 插件增加对应测试和 Android 仪器测试。
- 使用 MockWebServer 验证 Cookie、域名/路径、重定向、请求头、退出、会话恢复、恶意来源和无登录来源回归。真实网站测试必须单独列证据；不可伪造。
- APK 长期维护自有签名，注意与官方签名不一致时可能无法直接覆盖安装；不得以清空用户数据作为更新方案。
- 每轮结束汇报：完成项、修改文件、核心改动理由、上游冲突风险、测试证据、缺陷与未验证项、本地 Commit。未经用户许可不要推送远程。

**验收底线**：登录功能可选、安全、真实可测试；原有阅读行为不退化；上游更新时主要调整少量适配点，而不是反复重写整套认证逻辑。
