# -*- coding: utf-8 -*-
"""
用真实的教务系统课表页 HTML 验证解析逻辑（Python 等价实现）。

对应 HomeFragment.injectParser() 里的 parseItemDetails：
  - tooltip 取不到（它是 ul 的兄弟，不是 li 的兄弟），改为读 item 自身的
    qz-hasCourse-detailitem
  - 无前缀的 '01~02小节' 靠后缀识别为 sections
  - '[1-16周] 星期二' 靠首字符 '[' 识别为 weekRange
  - '瑞樟4瑞樟4-101' 靠「不含全角冒号」识别为 location，再走 dedupPrefix
  - 当前周次与可选周次从页面已选中的 option 读出

用法:
    python verify_parser.py <课表页.html>
  或设置环境变量 KB_HTML 指向该文件；
  都没有时按桌面/下载目录里的常见文件名找。
"""
import os
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

DEFAULT_CANDIDATES = [
    Path.home() / "Desktop" / "学生端-个人中心.html",
    Path.home() / "Downloads" / "学生端-个人中心.html",
]


def resolve_html_path():
    """按 命令行参数 -> 环境变量 -> 常见位置 -> 桌面/下载下递归查找 的顺序定位。

    最后一步是为了抗目录调整：文件被收进 Desktop/课表获取/ 这类子目录后，
    固定路径就失效了（确实发生过）。
    """
    if len(sys.argv) > 1:
        return Path(sys.argv[1])
    env = os.environ.get("KB_HTML")
    if env:
        return Path(env)
    for p in DEFAULT_CANDIDATES:
        if p.exists():
            return p
    for base in (Path.home() / "Desktop", Path.home() / "Downloads"):
        if not base.is_dir():
            continue
        for depth in ("*", "*/*", "*/*/*"):
            for hit in base.glob(depth + "/学生端-个人中心.html"):
                if hit.is_file():
                    return hit
    return DEFAULT_CANDIDATES[0]


HTML_FILE = resolve_html_path()
VOID = {"br", "img", "input", "meta", "link", "hr", "source", "template"}



class Node:
    def __init__(self, tag, attrs=None, parent=None):
        self.tag = tag
        self.attrs = dict(attrs or {})
        self.children = []
        self.parent = parent

    @property
    def text_content(self):
        return "".join(c if isinstance(c, str) else c.text_content for c in self.children)

    @property
    def classes(self):
        return (self.attrs.get("class") or "").split()

    def has_class(self, c):
        return c in self.classes

    def iter_descendants(self):
        for c in self.children:
            if isinstance(c, Node):
                yield c
                yield from c.iter_descendants()

    def query_all(self, sel):
        t, cl = parse_sel(sel)
        return [n for n in self.iter_descendants() if n.tag == t and (cl is None or n.has_class(cl))]

    def query(self, sel):
        r = self.query_all(sel)
        return r[0] if r else None


def parse_sel(sel):
    last = sel.strip().split()[-1]
    if "." in last:
        a, b = last.split(".", 1)
        return a.upper(), b
    return last.upper(), None


class TB(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("ROOT")
        self.stack = [self.root]

    def handle_starttag(self, tag, attrs):
        n = Node(tag.upper(), attrs, self.stack[-1])
        self.stack[-1].children.append(n)
        if tag.lower() not in VOID:
            self.stack.append(n)

    def handle_startendtag(self, tag, attrs):
        n = Node(tag.upper(), attrs, self.stack[-1])
        self.stack[-1].children.append(n)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag.upper():
                del self.stack[i:]
                return

    def handle_data(self, data):
        if data:
            self.stack[-1].children.append(data)


# ---- 与 Java 中 dedupPrefix 完全等价的实现 ----
def dedup_prefix(s):
    if not s:
        return s
    n = len(s)
    for k in range(2, n // 2 + 1):
        if n - 2 * k < 1:
            break
        p = s[:k]
        if s.startswith(p + p):
            return p + s[2 * k:]
    return s


def parse_item_details(item, course):
    """对应 JS 里的 parseItemDetails（dayDate 已改为直接取表头日期）。"""
    for div in item.query_all("div.qz-hasCourse-detailitem"):
        t = div.text_content.strip()
        if not t:
            continue
        if t.startswith("教师："):
            if not course["teacher"]:
                course["teacher"] = t[3:]
        elif t.endswith("小节"):
            if not course["sections"]:
                course["sections"] = t
        elif t.startswith("["):
            if not course["weekRange"]:
                course["weekRange"] = t
        elif "：" not in t and not course["location"]:
            course["location"] = t


def main():
    if not HTML_FILE.exists():
        print("找不到课表页 HTML: %s" % HTML_FILE)
        print("用法: python verify_parser.py <课表页.html>")
        print("  或设置环境变量 KB_HTML 指向该文件")
        return 1
    print("读取: %s" % HTML_FILE)
    raw = HTML_FILE.read_text(encoding="utf-8", errors="replace")
    tb = TB()
    tb.feed(raw)
    root = tb.root
    table = root.query("table.qz-weeklyTable")
    if not table:
        print("未找到 table.qz-weeklyTable")
        return 1

    # ---- 当前周次 ----
    current_week = ""
    sel_opt = None
    for o in root.iter_descendants():
        if o.tag == "OPTION" and "selected" in o.attrs:
            sel_opt = o
            break
    if sel_opt is not None:
        current_week = sel_opt.text_content.strip()
    if not current_week:
        for inp in root.iter_descendants():
            if inp.tag == "INPUT":
                v = inp.attrs.get("value") or ""
                if re.search(r"第.+周", v):
                    current_week = v
                    break

    print("=== 当前周次（直接读取，不推算）===")
    print("  currentWeek = %r" % current_week)
    print("  全部周次选项: %s" % [
        o.attrs.get("value") for o in root.iter_descendants()
        if o.tag == "OPTION" and o.attrs.get("value")
    ])

    # ---- 表头 ----
    headers = []
    ths = table.query_all("thead tr th.qz-weeklyTable-th")
    for i, th in enumerate(ths):
        if i == 0:
            continue
        sp = th.query_all("span")
        if len(sp) >= 2:
            headers.append((sp[0].text_content.strip(), sp[1].text_content.strip()))

    # ---- 解析课程 ----
    courses = []
    for row in table.query_all("tbody tr.qz-weeklyTable-tr"):
        tds = row.query_all("td.qz-weeklyTable-td")
        if not tds:
            continue
        slot = ""
        t0 = tds[0].query("div.index-title")
        if t0:
            slot = t0.text_content.strip()
        for di in range(1, min(len(tds), len(headers) + 1)):
            ul = tds[di].query("ul.courselists")
            if not ul:
                continue
            for item in ul.query_all("li.courselists-item"):
                nm = item.query("div.qz-hasCourse-title")
                name = nm.text_content.strip() if nm else "未知课程"
                c = {"teacher": "", "location": "", "weekRange": "", "sections": ""}
                parse_item_details(item, c)
                courses.append({
                    "name": name,
                    "teacher": c["teacher"] or "未知教师",
                    "location": dedup_prefix(c["location"]) or "未知地点",
                    "weekRange": c["weekRange"],
                    "dayOfWeek": headers[di - 1][0],
                    "dayDate": headers[di - 1][1],
                    "timeSlot": slot,
                    "sections": c["sections"] or slot,
                })

    print("")
    print("=== 解析结果 ===")
    print("  课程条目数: %d" % len(courses))
    filled = {k: 0 for k in ("teacher", "location", "weekRange", "sections")}
    for c in courses:
        for k in filled:
            if c[k] and c[k] not in ("未知教师", "未知地点"):
                filled[k] += 1
    print("  字段填充率:")
    for k, v in filled.items():
        flag = "OK " if v == len(courses) else "!! "
        print("    %s%-10s %d / %d" % (flag, k, v, len(courses)))

    print("")
    print("  全部条目:")
    for c in courses:
        dup = bool(re.match(r"^(.{2,})\1", c["location"]))
        print("    [%s %s] %s" % (c["dayOfWeek"], c["dayDate"], c["name"]))
        print("        teacher=%r sections=%r" % (c["teacher"], c["sections"]))
        print("        weekRange=%r location=%r%s" % (c["weekRange"], c["location"],
                                                     "  <<< 仍重复" if dup else ""))

    dup_n = sum(1 for c in courses if re.match(r"^(.{2,})\1", c["location"]))
    print("")
    print("  地点仍重复的条目数: %d %s" % (dup_n, "(应为 0)" if dup_n == 0 else "<<< 有问题"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
