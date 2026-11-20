# 武夷学院课表助手 - KipKip

> 一款 Android 课表查询应用：自动登录教务系统、解析整学期课表、按日期展示今天与本周的课程。

---

## AI 生成声明

**本项目由 AI 辅助生成**

本项目是在 [DeepSeek AI] [Kimi AI] [GPT AI] 的辅助下完成的 Android 应用开发项目。从需求分析、架构设计到代码实现、调试优化，AI 全程参与了开发过程。

### AI 贡献范围

| 阶段 | AI 贡献 | 人工参与 |
|------|---------|----------|
| 需求分析 | 功能规划、技术选型 | 需求提出 |
| 架构设计 | 模块划分、流程设计 | 方案确认 |
| 代码生成 | Java / XML / 少量 JS 代码 | 参数调优 |
| 调试优化 | 错误分析、解决方案 | 测试验证 |
| 文档编写 | 技术报告、使用说明 | 内容审核 |

### 技术亮点（AI 辅助实现）

- **单点登录**：注入 JS 自动填表并提交 CAS 统一认证，成功后把 Cookie 交给原生层
- **接口直连**：拿到 Cookie 后绕过网页，直接请求课表接口，避免"等 DOM 渲染完"的时序问题
- **表格解析**：用 jsoup 按"列位置 = 星期几"的几何关系解析跨行课程，`rowspan` 不再干扰列对齐
- **本地滤波**：整学期课表一次取回，按日期与周次在本地筛选，跨周、单双周都不用再联网

### AI 工具信息

| 项目 | 信息 |
|------|------|
| AI 模型 | DeepSeek, Kimi, GPT |
| 开发工具 | Android Studio |
| 编程语言 | Java / XML |

---

## 项目简介

本项目是一款专为武夷学院学生设计的课表查询 Android 应用，解决教务系统无移动端、重复登录等问题。

### 核心功能

- **自动登录**：一次输入，后续自动登录
- **本日课表**：当天课程一目了然，粒子球显示当前抓取状态
- **周次课表**：整周大表格，可横纵滚动，点击课程查看地点、教师、节次、周次
- **周次切换**：任意跳到第 1~20 周，单双周课程正确过滤
- **账号验证**：首次使用需验证学号密码能否登录教务系统
- **系统公告**：接收服务器推送通知
- **版本更新**：启动时自动检查新版本（可在设置里关闭）
- **深色模式**：跟随系统明暗切换，界面无硬编码颜色

---

## 技术栈

| 技术 | 用途 | 版本 |
|------|------|------|
| Java | 全部界面与逻辑 | JDK 17 |
| Android SDK | 移动开发 | compileSdk 34 / minSdk 24 |
| Material Components | M3 组件与配色 | 1.12.0 |
| jsoup | 课表 HTML 解析 | 1.17.2 |
| OkHttp | 网络请求 | 4.12.0 |
| Gson | JSON 解析 | 2.10.1 |
| LiquidGlass | 底栏跟随手指的玻璃透镜 | v2.0.11 |
| WebView | 仅用于过单点登录（不可见） | 原生 |

---

## 快速开始

### 环境要求

- Android Studio Hedgehog | 2023.1.1+
- JDK 17+
- Android SDK API 34+

### 克隆项目

```bash
git clone https://github.com/yichenfan319-cloud/-APP.git
```

### 构建

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

> 若 Gradle 提示找不到 JDK 21，设置 `JAVA_HOME` 指向 Android Studio 自带的 JBR 即可
> （例如 `D:\Android\jbr`），避免联网下载 JDK。

---

## 主要文件

（只列核心部分，其余见仓库）

```
app/src/main/java/com/wuyi/kebiao/
├── MainActivity.java        三页容器 + 玻璃珠拖动导航
├── TodayFragment.java       本日页：粒子球 + 当日课程
├── WeekFragment.java        周次页：周次选择 + 课表表格
├── AccountFragment.java     账号页：学号密码与验证
├── ScheduleEngine.java      隐藏 WebView 过 SSO，之后直连接口取数
├── ScheduleStore.java       课表与状态的进程内单例
├── TimetableRepository.java 接口请求、学期第一周校准、按日期查询
├── TimetableParser.java     课表 HTML 解析（jsoup）
├── TimetableGridView.java   自绘周课表（双向滚动、点击命中）
├── ThinkingOrbView.java     自绘粒子球（五态）
├── SystemBars.java          状态栏配色与图标明暗
├── Prefs.java / Persist.java 本地存档
└── VersionChecker.java      版本检查

app/src/test/java/com/wuyi/kebiao/
├── TimetableParserTest.java      14 项
├── TimetableWeekFilterTest.java  17 项
└── TimetableRepositoryTest.java  12 项
```

## 致谢

- [Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) —— 底栏玻璃透镜
- [thinking-orbs](https://github.com/Jakubantalik/Libraries.dev) —— 粒子球的形态参考

## 许可证

本项目使用 MIT License，详见 [LICENSE](LICENSE)。
