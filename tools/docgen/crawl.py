"""Breadth-first crawl of the QQ bot API v2 docs, saving every same-prefix HTML page locally."""
import re
import os
import json
import urllib.request
from collections import deque
from urllib.parse import urljoin, urlparse, urldefrag

BASE = 'https://bot.q.qq.com/wiki/develop/api-v2/'
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'site')
PREFIX = '/wiki/develop/api-v2/'
MAX_PAGES = 600

opener = urllib.request.build_opener()
opener.addheaders = [('User-Agent', 'Mozilla/5.0'), ('Accept', 'text/html')]


def fetch(url):
    try:
        with opener.open(url, timeout=30) as r:
            return r.read().decode('utf-8', 'replace')
    except Exception as e:  # noqa: BLE001
        return '<<ERR %s>>' % e


def slug(url):
    p = urlparse(url).path
    if p.endswith('/'):
        p += 'index.html'
    return PREFIX.strip('/') + p[len(PREFIX):] if p.startswith(PREFIX) else p


def main():
    os.makedirs(OUT, exist_ok=True)
    seeds = [urljoin(BASE, 'index.html'), BASE, urljoin(BASE, 'autogen/index.html')]
    # the sidebar only links the pages of the current section, so harvest the autogen index too
    for candidate in ('autogen/index.html',):
        html_text = fetch(urljoin(BASE, candidate))
        for m in re.findall(r'href="([^"]+)"', html_text):
            absu = urldefrag(urljoin(BASE, m))[0]
            if urlparse(absu).path.startswith(PREFIX) and absu.endswith('.html'):
                seeds.append(absu)
    seen, saved, queue = set(), {}, deque(seeds)
    pages = []
    while queue and len(seen) < MAX_PAGES:
        url = urldefrag(queue.popleft())[0]
        if url in seen:
            continue
        seen.add(url)
        if not urlparse(url).path.startswith(PREFIX) or not (url.endswith('.html') or url.endswith('/')):
            continue
        body = fetch(url)
        if body.startswith('<<ERR'):
            saved[url] = 'error: ' + body
            continue
        path = os.path.join(OUT, slug(url).replace('/', '__'))
        open(path, 'w', encoding='utf-8').write(body)
        links = set()
        for m in re.findall(r'href="([^"]+)"', body):
            if m.startswith(('javascript:', 'mailto:', '#')):
                continue
            absu = urldefrag(urljoin(url, m))[0]
            if urlparse(absu).path.startswith(PREFIX) and (absu.endswith('.html') or absu.endswith('/')):
                links.add(absu)
                pages.append((urlparse(absu).path, m))
        queue.extend(sorted(links - seen))
    mapping = sorted(set(pages))
    json.dump([p[0] for p in mapping], open(os.path.join(
        os.path.dirname(OUT), 'site_urls.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
    print('visited=%d saved=%d urls=%d' % (len(seen), len(os.listdir(OUT)), len(mapping)))


if __name__ == '__main__':
    main()
