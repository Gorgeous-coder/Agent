#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小红书实时搜索采集（Playwright + 系统 Edge，无 daemon）
- 搜索关键词 → 提取笔记卡片（含带 xsec_token 的完整链接）
- 进入前 N 篇笔记详情页提取正文（#detail-desc）
- 输出 JSON: [{"title","author","likes","url","content"}, ...]
- 全部异常吞掉，保证 stdout 输出可解析 JSON
用法:
  python redbook_crawl.py --session <ws> --a1 <a1> --webid <wid> --keyword "南京美食" --top 8 [--detail 5]
"""
import argparse
import json
import sys
import time
import urllib.parse

from playwright.sync_api import sync_playwright

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36')

DETAIL_SELECTORS = ['#detail-desc', '.note-text', '.desc']


# 旅行/穿搭主题词表：标题命中其一即认为与主题相关（配合城市名双重校验）
TOPIC_HINTS = ('攻略', '旅游', '旅行', '景点', '行程', '游玩', '一日游', '两日游', '三日游',
               '美食', '穿搭', '穷游', 'citywalk', '逛吃', '打卡', '探店', '好玩', '推荐')


def _relevant(title, keyword):
    """标题与关键词是否相关（城市 + 主题双重校验，防假阳性）。

    旧逻辑只要求标题含关键词前 2 字（城市名），导致搜"南京旅游攻略"时
    "国足在南京奥体…"这类含城市名但主题无关的帖子也能混进来。
    现在必须同时命中：城市（前 2 字） AND 主题词（攻略/旅游/穿搭/景点等词表，
    加上关键词主题段的 2 字切分），例如搜"南京 旅游攻略" → 标题须含"南京"
    且含"攻略/旅游/旅行/景点…"之一。
    用于过滤小红书 cookie 失效/风控时混入的"猜你喜欢"推荐流。
    """
    if not title or not keyword:
        return False
    kw = keyword.replace(' ', '').strip()
    if not kw:
        return False
    parts = [x for x in keyword.split() if x]
    city = parts[0] if parts else kw
    # 城市必须命中（取前 2 字，兼容"南京"/"上海"/"哈尔滨"等）
    if (city[:2] not in title) and (city not in title):
        return False
    low = title.lower()
    if any(h in low for h in TOPIC_HINTS):
        return True
    # 自适应：关键词主题段（如"旅游攻略"→"旅游/游攻/攻略"）2 字切分也认
    topic = kw[len(city):] if len(kw) > len(city) else ''
    if len(topic) >= 2:
        for i in range(len(topic) - 1):
            if topic[i:i + 2] in low:
                return True
    return False


# ===== 笔记链接策略（重要，实测结论 2026-08-27）=====
# 链接统一输出「规范长链」 https://www.xiaohongshu.com/discovery/item/<note_id>?xsec_token=...
# 实测结论：
#   1) 微信内点开：小红书对该长链走官方 OAuth，redirect_uri 完整保留笔记地址+token，
#      授权后直接落到原帖（与官方分享行为一致）；
#   2) 浏览器（登录/未登录）：直接打开笔记正文页（已实测）；
#   3) 小红书 App：识别 discovery/item 原生格式，可直接打开。
# 对比 xhslink.com/a/<code> 短链（官方 share/code 接口换的真码）：在微信/浏览器里
# 实测 307 后落到 explore 首页推荐流（满屏无关内容，如国足），必须弃用。
# 因此脚本不再生成短链；Java 端也不得改写为任何 xhslink 格式（尤其 xhslink.com/o/<id> 伪造格式）。


def fetch_detail(page, url):
    """进笔记详情页提取正文；失败返回空字符串（绝不抛异常）"""
    try:
        page.goto(url, wait_until='domcontentloaded', timeout=20000)
        try:
            page.wait_for_selector('#detail-desc', timeout=8000)
        except Exception:
            pass
        # 多选择器兜底
        for sel in DETAIL_SELECTORS:
            try:
                el = page.query_selector(sel)
                if el:
                    t = el.inner_text().strip()
                    if t and len(t) > 5:
                        return t
            except Exception:
                continue
    except Exception:
        pass
    return ''


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--session', required=True)
    p.add_argument('--a1', required=True)
    p.add_argument('--webid', required=True)
    p.add_argument('--keyword', required=True)
    p.add_argument('--top', type=int, default=8)
    p.add_argument('--detail', type=int, default=8, help='进入前 N 篇详情页提取正文（默认与 top 一致）')
    args = p.parse_args()

    cookies = [
        {'name': 'web_session', 'value': args.session, 'domain': '.xiaohongshu.com', 'path': '/'},
        {'name': 'a1', 'value': args.a1, 'domain': '.xiaohongshu.com', 'path': '/'},
        {'name': 'webId', 'value': args.webid, 'domain': '.xiaohongshu.com', 'path': '/'},
    ]
    url = ('https://www.xiaohongshu.com/search_result?keyword='
           + urllib.parse.quote(args.keyword)
           + '&source=web_search_result_notes'
           + '&sort=popularity_descending')  # ✅ 按热度排序：返回当下热度高的笔记

    items = []
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(channel='msedge', headless=True)
            try:
                ctx = browser.new_context(user_agent=UA, viewport={'width': 1280, 'height': 900}, locale='zh-CN')
                ctx.add_cookies(cookies)
                page = ctx.new_page()
                page.goto(url, wait_until='domcontentloaded', timeout=30000)
                try:
                    page.wait_for_selector('section.note-item', timeout=20000)
                except Exception:
                    pass
                time.sleep(3)
                # ⚠️ 不滚动：第一屏就有 20+ 条，取 top 8 足够；滚动会加载"推荐 feed"
                # （跟关键词无关的内容，比如搜"美食"滚着滚着出现连云港），导致无关链接。
                # 提取卡片：优先选带 xsec_token 的完整链接（无 token 的 explore 链接正文加载不出来）
                items = page.eval_on_selector_all(
                    'section.note-item',
                    """els => els.map(n => {
                        const a = n.querySelector('a[href*="xsec_token"]')
                              || n.querySelector('a[href*=search_result]')
                              || n.querySelector('a[href*=explore]');
                        const t = n.querySelector('.title');
                        // 作者名 + 点赞：.author 里带日期，取内层 .name 更干净
                        const au = n.querySelector('.author .name') || n.querySelector('.author');
                        const lk = n.querySelector('.like-wrapper .count');
                        return {title: t ? t.textContent.trim() : '',
                                author: au ? au.textContent.trim() : '',
                                likes: lk ? lk.textContent.trim() : '',
                                url: a ? a.href : ''};
                    })""")
                items = [x for x in items if x.get('title') and x.get('url')][:args.top]
                # ===== 相关性校验（防"推荐流/风控"返回无关内容）=====
                # 小红书 cookie 失效 / 被风控时，搜索页可能加载的是"猜你喜欢"推荐流（跟关键词无关）。
                # 校验：城市 + 主题双重命中（见 _relevant），否则视为无效结果清空。
                items = [x for x in items if _relevant(x.get('title', ''), args.keyword)]
                if len(items) < 3:
                    # 有效结果太少 → 大概率被风控/未登录，返回空让上层友好提示，绝不给无关链接
                    items = []
                # ===== 逐篇提取正文（链接为规范长链，无需二次加工）=====
                for idx in range(min(len(items), args.detail)):
                    it = items[idx]
                    if not it.get('url'):
                        it['content'] = ''
                        continue
                    it['content'] = fetch_detail(page, it['url'])
            finally:
                browser.close()
    except Exception:
        items = []

    # 过滤空结果（cookie 失效 / 风控 / 无结果统一返回空数组，由调用方给友好提示）
    # Windows console 默认 gbk，遇到部分汉字（含 U+2757 ❗ 等）会 UnicodeEncodeError 退出。
    # 必须强制 utf-8 输出。同时把 traceback 也走 stderr 不污染 stdout。
    try:
        sys.stdout.reconfigure(encoding='utf-8')
        sys.stderr.reconfigure(encoding='utf-8')
    except Exception:
        pass
    sys.stdout.write(json.dumps(items, ensure_ascii=False))
    sys.stdout.flush()
    sys.exit(0)


if __name__ == '__main__':
    main()
