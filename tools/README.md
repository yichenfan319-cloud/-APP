# tools —— 离线验证脚本

这个目录里的脚本**不参与 APK 构建**，用途是在不依赖模拟器/真机的情况下，
校验两块最容易悄悄改坏的逻辑：

| 脚本 | 校验什么 | 运行方式 |
|---|---|---|
| `verify_parser.py` | 课表解析（字段提取 + 地点去重） | 需要一份教务系统课表页 HTML |
| `test_dedup_prefix.js` | `dedupPrefix()` 去重算法 | 直接跑，自带真实数据 |
| `test_frontend_render.js` | `index.html` 的筛选/渲染逻辑 | 直接跑，直接读工程的 `index.html` |

## 为什么需要它们

模拟器上的 WebView 在部分 16KB 页镜像里会原生崩溃（tombstone 显示
`libwebviewchromium.so` 在 `onTrimMemory` 中执行非法指令），
而且真机调试一轮成本很高。这几块是纯逻辑，离线跑更快也更可靠。

它们已经各自抓到过真实缺陷：

- **去重算法**：第一版判据写成 `s.startsWith(p + p.substring(1))`，
  整体错位一位，把「未知地点」改成「未地点」；第二版又会把
  `A101A101` 这种房间号截断成 `A101`。
- **解析器**：`weekRange` 与 `sections` 长期为空，根因是
  `<div class="qz-tooltip">` 是 `<ul class="courselists">` 的兄弟而非
  `<li>` 的兄弟，`item.nextElementSibling()` 恒为 null，
  那段读取逻辑从未执行过。

## 运行

### 1. 课表解析验证（Python）

需要一份已下载的教务系统课表页 HTML（在浏览器登录后「另存为」即可）。

```bash
# 默认路径见脚本内 HTML_FILE；也可以临时改掉它
python tools/verify_parser.py
```

输出会给出四个字段的填充率与全部条目。**期望值**：

```
OK teacher    11 / 11
OK location   11 / 11
OK weekRange  11 / 11
OK sections   11 / 11
地点仍重复的条目数: 0
```

条目数随学期不同，但填充率应为满值、重复数应为 0。

### 2. 去重算法验证（Node）

```bash
node tools/test_dedup_prefix.js
```

**期望**：`命中 10/10`、`误伤 0 个`、`不幂等 0 个`。

### 3. 前端渲染验证（Node）

直接读取 `app/src/main/assets/www/index.html` 里真实的脚本，
在最小 DOM 垫片上执行，喂入真实课表数据。

```bash
node tools/test_frontend_render.js
```

**期望**：解析出 11 门课程、`board` 渲染出课程卡片、周一到周五命中数相加等于总数。

> 注意：该脚本的 DOM 垫片只做末段 token 选择器匹配，不支持祖先匹配。
> 用它判断「筛选/渲染逻辑对不对」是可靠的；**不要**用它判断列归属
> 这类依赖真实 DOM 层级的问题。

## 改动相关代码后请重跑

- 动了 `HomeFragment.injectParser()` → 跑 `verify_parser.py`
- 动了 `dedupPrefix` → 跑 `test_dedup_prefix.js`
- 动了 `index.html` 的渲染/筛选 → 跑 `test_frontend_render.js`

这三个检查加起来不到一秒，比装一次 APK 划算得多。
