# KipKip 开发说明

给未来的自己（或接手的人）看的交接文档。记录**为什么这么做**，
而不只是做了什么 —— 代码本身能告诉你"是什么"，但说不清当时为什么选它。

---

## 一、这是什么

武夷学院课表 App。核心是一个**课表抓取器**：

```
HomeFragment
  ├─ webView（前台显示）  ← 加载 assets/www/index.html，整个界面都在这里
  ├─ crawlerView（后台隐藏）← 登录教务系统、抓课表、注入解析脚本
  └─ FrontBridge / BackBridge ← Java 与 JS 之间的双向通道
```

**界面主体是 WebView 里的 HTML**，不是原生 View。原生只提供外壳
（MainActivity 容器 + SettingsActivity 设置页）。

---

## 二、跑起来必须知道的三件事

### 1. 编译要用 JDK 21，不是 25

`settings.gradle.kts` 启用了 `foojay-resolver`，它想联网下载 JDK 21 却常被墙。
**本机 Android Studio 自带的 JBR 就是 21**，直接用它：

```powershell
$env:JAVA_HOME = "D:\Android\jbr"
$env:ANDROID_HOME = "C:\Users\123\AppData\Local\Android\Sdk"
.\gradlew.bat assembleDebug --no-daemon
```

> 或在 `gradle.properties` 加一行
> `org.gradle.java.installations.paths=D:/Android/jbr` 一劳永逸（尚未添加）。

### 2. 别用那个 16KB 页的模拟器

`Pixel_4`（`sdk_gphone16k_x86_64`）是 **16KB 页**实验镜像，
WebView 会在 `onTrimMemory` 里原生崩溃：

```
signal 4 (SIGILL)
#00 libwebviewchromium.so
#08 WV.qi1.onTrimMemory
```

真机是 **4KB 页**，不会走这条路径。**用真机调试。**

### 3. 改了逻辑先跑离线校验，别急着装 APK

`tools/` 下有三个脚本，合计不到一秒：

```powershell
$env:PYTHONIOENCODING = "utf-8"
python tools/verify_parser.py "路径/课表页.html"   # 解析 + 地点去重
node   tools/test_dedup_prefix.js                  # 去重算法
node   tools/test_frontend_render.js               # 前端渲染 + 彩蛋 + 周次标签回归
```

它们各自抓到过真实缺陷（详见 `tools/README.md`）。
**装一次 APK 的成本远高于跑这三个脚本。**

---

## 三、Java ↔ JS 的契约（改 HTML 时必须保住）

元素 id：

```
login-page  main-page  loginLog  runLog  loginBtn  username  password
rememberCheckbox  clearCredentialsBtn  todayBtn  tomorrowBtn  weekBtn
refreshBtn  logoutBtn  board  weekPicker  weekLabel  settingsBtn
```

CSS 类：`.btn .txt`、`.btn .loading`、`.log .line`（`.err` / `.warn` / `.egg`）

全局 JS 函数：

- `window.receiveFromAndroid(type, data)` —— Java 唯一的回传入口
  - `type` 取值：`courses` / `log` / `status` / `error` / `saved_credentials`
- `window.__parseTimetable()` —— 解析脚本的具名入口，周次切换后会再调一次
- `window.onWeekChosen(value, label)` —— 原生周次弹层选完后回调

Android 桥方法（`FrontBridge`）：`login` / `refresh` / `relogin` /
`getSavedCredentials` / `clearSavedCredentials` / `openSettings` /
`selectWeek` / `requestWeekPicker` / `openWeekView`

---

## 四、几个"看着奇怪但别乱改"的地方

### 解析器为什么要读 `qz-hasCourse-detailitem` 而不是 tooltip

真实 DOM 结构：

```html
<td>
  <div class="td-cell">
    <ul class="courselists">
      <li class="courselists-item">        ← 课程项（detailitem 在这里面）
    <div class="qz-tooltip">                 ← tooltip 是 ul 的兄弟，不是 li 的！
```

所以 `item.nextElementSibling()` **恒为 null**，原来那段读 tooltip 的逻辑
从来没执行过 —— 这就是 `weekRange` / `sections` 一直是空的根因。

### 为什么有 `dedupPrefix()`

教务系统返回的地点文本**本身就重复**：`瑞樟4瑞樟4-101`、`体育馆体育馆[篮球场]`。
不是解析器拼出来的。判据是 `s === 前缀 + 前缀 + 剩余`，
并额外要求「前缀至少 2 字符」且「重复后还有剩余」，
以免把 `A101A101` 这种完整房间号截断。

### 为什么 `showMainPage()` 里没有清空 `coursesData`

曾经有，是个 bug：`onCourses` 的执行顺序是
「解析 → 存进 `window.coursesData` → `showMainPage()` 清空 → `showToday()` 筛选」，
结果永远是 0 门课。**清空只能在切换账号时做。**

### 为什么当前周次以"用户选的"为准

`payload.currentWeek` 读自 `<select selected>`，而该属性是**服务器渲染时写死的**；
`se()` 异步加载新表格时不会更新它。用它会把你选的「第一周」改回「第七周」。
所以前端用 `chosenWeek` / `chosenWeekAt` 记录用户选择并拒绝陈旧值覆盖。

### 为什么周次切换是调页面的 `se()` 而不是直接请求 URL

那个 URL 带 `kbjcmsid`（随课表模式变）与 `xnxq01id`（每学期变）。
复用页面自己的逻辑就不必写死这两个会过期的参数。
等待策略是 `MutationObserver` 盯 `#kbLoading`，不是固定 sleep。

### 为什么彩蛋在前端而不是 Java

Java 侧在 `onCreateView` 里推的日志会被 `showMainPage() -> clearLogs()` 冲掉
（实测日志区为空）。必须由数据到达或 `DOMContentLoaded` 触发。

---

## 五、还没做的事

| 项 | 说明 |
|---|---|
| **按周过滤课程** | 现在切到第 8 周仍会显示单周课（如大学英语3 是 `[1,3,5,7,...周]`）。数据里已有 `weekRange`，随时可做 |
| **`gradle.properties` 加 JBR 路径** | 省掉每次设 `JAVA_HOME` |
| **版本号** | 当前 `1.3.6-beta`。设计稿上写的是 `1.2.6-beta`，未确认是否有意 |
| **周课表独立 Activity** | `WeekViewActivity` 已不在首页流程中（周视图内嵌了），保留以防回退 |
| **孤儿资源** | `nav_item_tint.xml` / `nav_item_text.xml`（底部导航已删）、`bg_hint_card.xml`、`character_*.png`（武院娘已删）、`border_bottom.xml`（本身有 bug：两层 solid 都铺满，做不出下边框） |
| **明文 HTTP** | 原有 `http://47.105.75.20:xxxx` 的请求（公告、版本检查）未走 HTTPS |

---

## 六、已知的坑（我踩过的）

1. **XML 注释里不能有连续两个连字符**（`------` 分隔线会让资源编译失败）。
2. **自定义主题属性必须先建 `values/attrs.xml` 声明**，否则
   `style attribute 'attr/colorXxx' not found`。
3. **`values-night/themes.xml` 的 style 名必须与 `values/` 完全一致**，
   否则整个文件形同虚设（本项目历史上就叫错过：`Base.Theme.Kipkip2`）。
4. **`NavigationBarView` 是抽象类，XML 无法实例化**，会启动闪退；
   该用具体的 `BottomNavigationView`。
5. **WebView 里 `window.prompt()` 需要 `WebChromeClient.onJsPrompt`**，
   不重写的话点了毫无反应 —— 别用它做交互。
6. **PowerShell 里 `$(...)` 会被当子表达式执行**，
   写 git commit message 时务必用 here-string + `WriteAllText(UTF8 无 BOM)`。
7. **`adb shell input tap` 对 WebView 内部元素不一定生效**，
   别依赖它验证界面交互。
