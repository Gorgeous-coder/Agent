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


def _relevant(title, keyword):
    """标题与关键词是否相关：标题包含关键词前 2 字（城市名）或完整关键词（去空格）即算相关。
    用于过滤小红书 cookie 失效/风控时混入的"猜你喜欢"推荐流。"""
    if not title or not keyword:
        return False
    kw = keyword.replace(' ', '').strip()
    if not kw:
        return False
    probes = {kw}
    if len(kw) >= 2:
        probes.add(kw[:2])   # 城市名（如"南京"）
    for p in probes:
        if p and p in title:
            return True
    return False


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
                # 校验：标题必须包含关键词的前 2 个字（城市名）或完整关键词，否则视为无效结果清空。
                items = [x for x in items if _relevant(x.get('title', ''), args.keyword)]
                if len(items) < 3:
                    # 有效结果太少 → 大概率被风控/未登录，返回空让上层友好提示，绝不给无关链接
                    items = []
                # 逐篇提取正文（前 --detail 篇，控制总时长）
                for idx in range(min(len(items), args.detail)):
                    if items[idx].get('url'):
                        items[idx]['content'] = fetch_detail(page, items[idx]['url'])
                    else:
                        items[idx]['content'] = ''
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
