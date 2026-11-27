# 主分支基线、番茄兼容与搜索优先级

## 基线

`origin/main` 为 `8b4625156d6c737eaafd6623fbabe0016287175f`，版本 `0.6.3`。同步时工作区干净。旧 `feature/source-auth` 为 `70aba65`，两侧分别有 57 和 15 个独有提交。`codex/main-cookie-search-priority` 直接从最新 `origin/main` 建立，未合并旧分支。历史提交保留供查证。

当前基线只有 `:app`、`:core`、`:reader`、`:baselineprofile`。旧 `:source-auth` 的 36 个跟踪文件、`:app` 的旧模块依赖、旧认证 Koin 绑定和专用验证工作流均未进入当前分支。旧构建缓存已移到忽略目录。继续使用主分支的 `SourceLoginSessions`、`SourceSessionStore`、WebView profile 和 Cookie 桥。

## 番茄规则

按 GitHub 最新正式 Release 验证参考快照 `v3.2.34`，发布时间 `2026-11-19T03:49:40Z`，核验时间 `2026-11-27T13:39:49.914Z`。`books.json` 10019965 字节，SHA-256 `0f2a1564bcd9a55ae5f9ac0e409a2bb0becd3e1bbedc5128fba71f1df8377571`。`clean_rules.json` 505864 字节，SHA-256 `5ac662fa9177098625036bffde2719e5c0eb4a9e5528f5496a75810d323fd1e6`。与上个核验快照相比，规则增删改均为 0。

旧分支的实机网络记录曾观察到，删除 Cookie 前后、重启和重新登录保存后，该规则的搜索均请求 `api.fanqiesdk.com`，出站无 Cookie 和 Authorization，番茄网页会话位于 `fanqienovel.com`。本轮在 `main` 上用同一参考规则与本地响应验证，公开搜索路径可以解析结果。搜索可用不能当作登录凭据仍在的证据。删除、更新和重新同步使用主分支现成接口，番茄和其他来源均沿用平台会话管理。

本轮用该规则复现详情脚本依赖的 `Packages` 兼容缺口。逐步确认 UUID、字符集、有界 GZIP 内存流与二进制 POST。修复覆盖现有脚本兼容层。二进制 POST 通过已绑定来源的 `EngineJsHttp` 与 `SourceHttpClient`，应用原有域名、TLS、私网和认证头限制，仅提交一次，无原生 Java 网络句柄、磁盘访问或主机特例。修复前详情断言失败，修复后受控搜索、详情及目录通过，不作为真实站点账号或章节校验。

## 搜索调度

`main` 的置顶只影响 `sortOrder`，在限流、健康状态和测速之后参与原有调度。本次新增优先标签。来源详情可设置或取消，来源管理可通过「优先搜索」筛选查看。书架网络书详情可标记，搜索完整书名时优先其当前绑定来源。书籍和来源只保存本机标识，关闭总开关会停用调度并保留标签。

首轮全局搜索、首轮继续搜索、手动换源复用原执行器和并发上限。先执行标记来源，全部结束后才执行普通队列。常规来源继续用主分支排序，标记来源失败或无结果按原有方式收尾。两队互斥，不重复发出同一来源的同页任务。搜索结果排序、去重、合并、分页与展示保留主分支行为。书架阅读继续绑定所选来源，手动换源只在选中候选后更新该绑定。

## 构建和回归

使用 JDK 21。设置 `INKWELL_AUTH_SNAPSHOT` 指向本轮核验的 `books.json` 后执行：

```powershell
.\gradlew.bat --offline --no-configuration-cache --max-workers=4 -I tmp/inkwell-isolated-phone.gradle :core:test :reader:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat --offline --no-configuration-cache --max-workers=4 :app:assembleDebug :app:assembleDebugAndroidTest
```

- 两次构建均通过。共 1,287 项 JVM 测试通过（`:core` 646、`:reader` 107、`:app` 534），无失败、错误或跳过。网络夹具验证了优先请求结束前普通请求不会发出、失败后普通请求继续执行，以及番茄参考规则能解析搜索、详情和目录响应。
- Redmi 设备安装独立调试应用 `com.radium.inkwell.validation`，保留原应用及其数据。`SourceLoginSessionsTest` 两项通过，真实 WebView 使用合成会话验证来源隔离、删除后读取不到旧值、重新创建会话管理器仍未恢复旧值、重新保存只读取新值。测试过程中未使用真实账号。
- `SearchPriorityPreferencesDeviceTest` 分两次启动写入和读取，各一项通过。手动打开独立应用的番茄来源详情，启用「优先搜索此来源」，并在来源管理的「优先搜索」筛选中确认番茄仍可见。总开关入口同样显示正常。
- 常规调试 APK 的应用 ID 为 `com.radium.inkwell`，版本名为 `0.6.3`，版本代码为 `603999`。本地调试签名与手机原应用签名不同，实机使用独立调试 APK。
- 本轮未使用真实站点账号验证登录、章节和令牌刷新，未完成其他大厂来源的逐站实测、主题及系统字体缩放回归。页面和规则更新仍需按最新快照复验。

## 本地交付

- 重新查询 GitHub 最新正式 Release，确认仍为 `v3.2.34`。已按 GitHub 返回的大小和 SHA-256 校验 `books.json`，从「大厂正版」分组提取 11 条，保留原始字段和顺序。文件为 `tmp/deliverables/legado-big-publishers-v3.2.34.json`，SHA-256 为 `e83b9ab95b226e90884d68f928bc94c8533d070be502da4220d974b9175fc6c4`。
- `./gradlew.bat --no-configuration-cache --max-workers=4 :app:assembleRelease` 构建成功，包含 R8、资源压缩及 Release Lint。`aapt2` 验证包名 `com.radium.inkwell`、版本 `0.6.3`（`603999`）及关闭调试标记，`apksigner` 验证签名成功。项目在未配置 CI 私钥时采用本地调试签名，与此前本地 `0.6.0` Release 相同，与手机现装的官方包签名不同。文件为 `tmp/deliverables/inkwell-0.6.3-fanqie-search-priority-release.apk`，SHA-256 为 `8afce9f46d981de608a1295822c321569dfb53e565658f277576c7562d40075f`。本轮只生成本地交付文件，未安装 Release APK。

## Release 回归问题

用户自行安装后，已通过 APK 哈希确认手机运行的是上述首版 Release。番茄全链路测试搜索到 10 条，首条《都市桃源》报「目录规则未匹配到章节」。同期 HTTP 记录包含搜索接口和作品网页的 HTTP 200，未观察到该次脚本的二进制 POST。番茄公开作品页有目录。

首版 Release 的 R8 映射显示 `GuardedPostUrl` 与 `MemoryLineReader` 被重命名，`GuardedPostConnection` 也没有保留。Rhino 根据原包名做沙箱检查、按脚本里的方法名反射调用，JVM 和 Debug 包无法覆盖这类压缩问题。已将三个脚本桥对象纳入现有 R8 保留规则，并加入开启压缩的 Android 测试，用本地合成响应运行 JavaScript、写入 GZIP 请求并读取返回文本。来源 JSON 和 Cookie 代码均无需变更。

修正版执行 `./gradlew.bat --offline --no-configuration-cache --max-workers=4 :core:test :reader:test :app:testDebugUnitTest :app:assembleRelease`，构建及 1,287 项 JVM 测试通过。修正后常规 Release 的 R8 映射保留三个类的原名，`aapt2` 与 `apksigner` 验证包名、版本和签名与首版相同。修正版文件为 `tmp/deliverables/inkwell-0.6.3-fanqie-rhino-release.apk`，SHA-256 为 `ac4524e5cd6d08e459b4313cb1fc6464a14b8f2b12fd40deb7b3b164dd9788d7`。

另用 `-I tmp/inkwell-self-release-phone.gradle :app:assembleRelease` 构建独立且开启 R8 的验证包，在 Redmi 实机用 Android 自带的 Instrumentation 执行同一测试逻辑，结果 `OK (1 test)`。脚本发送的 GZIP 请求可解包为预期数据，返回文本可通过 JavaScript 读取。该测试使用拦截器内的合成响应，未复测番茄真实目录和章节。用户主应用仍保留原安装及数据。

## 正文复测

手机安装修正版后核对 APK 哈希，确认与本地交付一致。手机 MCP 显示校验进入正文后报「正文规则未匹配到内容」。手机请求记录显示搜索、详情和目录请求均返回 HTTP 200，校验已进入正文阶段。真实记录未观察到该次正文脚本向客户端正文接口发出请求。

在 `:core:test --tests '*FanqieReferenceRuleTest'` 中临时加入合成正文探针，复用同一导入规则与拦截器。合成目录返回章节后，正文脚本在请求前因脚本沙箱未暴露 `BigInteger` 而中止，外层规则引擎将异常降为无匹配。该正文路径使用客户端签名和加密协议，未扩展沙箱或复刻客户端协议。公开章节可在番茄官网正常进入。此轮只记录正文定位结果，未修改生产代码、来源 JSON 或生成新 APK。

## 旧版实机证据

- 旧分支 `feature/source-auth` 的 `2026-11-18-通用脚本兼容与起点诊断.md` 记录，包含旧脚本替身的候选包在实机完成一次番茄全链路，正文为 7,608 字符。`2026-11-21-合并0.6.0与番茄Cookie排查.md` 记录 `0.6.0` 上另一次实机样本通过，正文为 7,609 字。两项均为单条样本，正文的逐请求认证材料未记录。
- 将旧缓存中的番茄公开页诊断副本与本轮导出的 `v3.2.34` 比对，搜索、详情、目录、正文和脚本库均一致，只有诊断副本未包含 `loginUrl` 和 `loginUi`。正文规则和脚本库分别具有相同的 SHA-256 前缀 `33c8f1b50407`、`27c611677aee`。旧实机记录使用当时手机已有来源，未导出完整规则或记录其哈希。
- 提交 `fbf5f5a` 增加过旧版脚本兼容对象，其中包括正文脚本用到的 `BigInteger`。当前以主分支为基线的实现未引入该正文能力，已从请求前的脚本异常复现。官网章节用网页字体渲染私用字符，直接提取网页文本无法复用现有的阅读排版。原生正文仍待有授权、可解析的正文渠道。

## 通用大整数兼容

在原有 Rhino `JdkCompat` 中加入 `Packages.java.math.BigInteger`。任意来源均可使用同一入口处理超出 JavaScript 安全整数范围的 ID 和计数。支持字符串、带符号字节数组和符号加幅值的构造，支持基本整数运算、比较及进制转换。输入和运算结果受 4,096 位上限保护，包装对象不返回原生 Java 类。整个实现不按来源名称、域名或 ID 分流，也不引入旧登录模块、随机密钥生成或站点专有正文协议。

通过远端只读核对确认 `origin/main` 仍为 `8b46251`，版本仍为 `0.6.3`。重新查询正式参考版本并校验 `books.json` 与 `clean_rules.json`，结果仍为 `v3.2.34`。新增测试用合成脚本覆盖精度、字节转换、边界和反射隔离，参考番茄来源测试仍只覆盖搜索、详情和目录的受控响应。番茄真实正文不在本轮验证结论内。

设置 `INKWELL_AUTH_SNAPSHOT` 后运行 `./gradlew.bat --offline --no-configuration-cache --max-workers=4 :core:test :reader:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`。1,289 项测试通过（核心 648、阅读器 107、应用 534），Debug 和 Release 构建成功。Release 的 R8 映射保留了脚本调用的大整数类及方法名，APK 仍为 `0.6.3`（`603999`），验签通过。本轮未安装或推送构建产物。

实机追加验证使用含正文解析夹具和边界测试的独立包 `com.radium.inkwell.releasevalidation`。通过 `-I tmp/inkwell-self-release-phone.gradle :app:assembleRelease` 构建并验签。首次安装遇到系统限制，用户允许 USB 安装后通过手机 MCP 安装成功，未覆盖日常使用的应用。`adb shell am instrument -w -r com.radium.inkwell.releasevalidation/com.radium.inkwell.data.source.SelfBridgeInstrument` 返回 `OK (3 tests)`。压缩后的包在 Redmi 实机通过了 GZIP 请求往返、合成来源正文中的大整数精度，以及字节符号和沙箱边界检查。本次使用合成响应，未验证番茄真实正文。

## 新版番茄来源实机复查

通过手机 MCP 读取独立验证包上的番茄全链路结果，并比对手机 APK 与大整数验证包的 SHA-256，一致。实际样本为《剑》（海棠树影），搜索 10 条、详情通过、目录 1 章，正文仍报「正文规则未匹配到内容」。同期请求记录含搜索、详情和目录 HTTP 200，未见正文接口请求。

针对同一参考规则再次用本地拦截器执行正文，上一轮的 `BigInteger` 未定义错误已消失，正文仍在请求前报调用 `undefined`。规则的两条正文分支均在请求前尝试构造用于客户端加密会话的 `SecureRandom`，该对象没有向来源脚本开放。此前的三项合成实机用例不能作为番茄正文通过的证据。样本的官方章节网页已核对返回 HTTP 200 和正文显示所需的页面标记。Inkwell 原生正文仍未通过。

## 正文接入验收条件

验收要求是从真实来源取得有授权的章节正文，在 Inkwell 原生阅读器中成功排版并翻页。本次最初的实机结果为搜索、详情与目录成功，正文失败。旧版样本能够读通。规则说明及代码表明它使用 App 接口，未查到面向第三方的公开文档，不能据此认定接口性质或把引擎本地异常归因为服务器的访问限制。[抖音小说内容库的公开说明](https://developer.open-douyin.com/forum/bulletin/post/67ac3df17adf9ea3643fc787)要求开发者上传已有小说内容，与此处的脚本引擎兼容问题分开记录。

## 旧版通用兼容迁移

对照旧版同时补齐有工作预算的模幂、安全随机数及 HTTPS 二进制读取。现有连接仍按来源作用域过滤会话数据，限制公网 HTTPS、响应大小和执行时间，保留 TLS 校验，保持一次请求且不自动跳转。构造随机数和模幂使用合成脚本覆盖。原番茄规则在拦截器中已越过本地脚本异常并发起正文分支请求，此结果尚未覆盖服务器是否返回可读正文。

## 最终实机验收与发布构建

- 使用开启 R8 的独立验证包，在番茄来源详情触发《剑》（海棠树影）的真实全链路测试。搜索 10 条、详情通过、目录 1 章、正文 142 段。原生阅读器打开同一来源的《骇夜：我在恐怖游戏里暴富》第一章，从 `3 / 11` 成功翻到 `4 / 11`。
- `:core:test :reader:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 通过，合计 1,291 项单元测试，无失败、错误或跳过。随后单独以常规应用 ID 构建 `:app:assembleRelease`，包名 `com.radium.inkwell`，版本 `0.6.3`（`603999`），验签通过，与此前本地 APK 的签名一致。文件 `inkwell-0.6.3-fanqie-reading-release.apk`，SHA-256 `004e2104036b66163858ebbb92bd384c0d13e2fffa4ba0a8f539660cb6f8ef93`。
- 独立附件 `legado-big-publishers-v3.2.34.json` 从 [luanmuc/legado-book-sources 的 v3.2.34 Release](https://github.com/luanmuc/legado-book-sources/releases/tag/v3.2.34) 的 `books.json` 提取「大厂正版」分组，共 11 条。仅作筛选，规则字段和顺序保留。附件 SHA-256 `e83b9ab95b226e90884d68f928bc94c8533d070be502da4220d974b9175fc6c4`。
