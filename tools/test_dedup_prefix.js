/*
 * 验证「地点前缀重复」的修复逻辑（第三版，最终）。
 *
 * 判据：存在 k（2 <= k <= floor(len/2)），使得
 *     s.startsWith(p + p)   其中 p = s[0..k)
 * 且   k < len - k   （即重复之后还有剩余内容，避免把「A101A101」这种
 *                      本身就是完整房间号的字符串截断）
 * 命中则修复为  p + s.substring(2k)
 *
 * 演进记录：
 *   v1 判据写成 s.startsWith(p + p.substring(1)) —— 整体错位一位，
 *      把「未知地点」改成「未地点」，被测试当场抓住。
 *   v2 判据正确，但会截断「A101A101」。
 *   v3 增加前缀最小长度与「必须还有剩余」两条约束。
 */
function dedupPrefix(s) {
    if (!s) return s;
    const len = s.length;
    const max = Math.floor(len / 2);
    for (let k = 2; k <= max; k++) {
        const p = s.substring(0, k);
        if (len - 2 * k < 1) break;          // 后面没有剩余内容了，不处理
        if (s.startsWith(p + p)) {
            return p + s.substring(2 * k);
        }
    }
    return s;
}

// 真实数据（自模拟器 logcat 抓到的课表 JSON）
const REAL = [
    "瑞樟4瑞樟4-101",
    "瑞樟11瑞樟11-301[报告型智慧教室]",
    "瑞樟6瑞樟6-509[计算机基础实验室14]",
    "瑞樟10瑞樟10-106",
    "体育馆体育馆[篮球场]",
    "瑞樟11瑞樟11-104[基础型智慧教室]",
    "瑞樟10瑞樟10-308",
    "瑞樟11瑞樟11-208[报告型智慧教室]",
    "屏山7屏山7-403[智慧教育技术实验室]",
    "瑞樟4瑞樟4-101[智慧教室]"      // 只有前缀重复，尾巴不参与
];

// 不应被改动的样本
const NEGATIVE = [
    "瑞樟4-101",
    "A101A101",        // 重复片段占满整个字符串 -> 保留
    "未知地点",
    "1号楼101",
    "教学楼A",
    "瑞樟10-106",
    "体育馆",
    ""
];

console.log('=== 应被去重（期望全部命中）===');
let ok = 0;
for (const s of REAL) {
    const fixed = dedupPrefix(s);
    if (fixed !== s) ok++;
    console.log(`  ${fixed !== s ? 'FIX ' : '!! 漏 !!'}  ${s}  ->  ${fixed}`);
}
console.log(`  命中 ${ok}/${REAL.length}`);

console.log('');
console.log('=== 不应被误伤（期望 0 误伤）===');
let bad = 0;
for (const s of NEGATIVE) {
    const fixed = dedupPrefix(s);
    if (fixed !== s) bad++;
    console.log(`  ${fixed !== s ? '!! 误伤 !!' : 'KEEP      '}  "${s}"  ->  "${fixed}"`);
}
console.log(`  误伤 ${bad} 个`);

console.log('');
console.log('=== 幂等性（期望 0 不幂等）===');
let nonIdem = 0;
for (const s of REAL.concat(NEGATIVE)) {
    const once = dedupPrefix(s);
    const twice = dedupPrefix(once);
    if (once !== twice) { nonIdem++; console.log(`  !! ${s} -> ${once} -> ${twice}`); }
}
console.log(`  不幂等 ${nonIdem} 个`);
