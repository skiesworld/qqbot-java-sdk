"""Turn the crawled QQ bot API v2 docs into spec.json (endpoints, events, shared types, examples)."""
import re
import os
import json
import html
import glob
import collections

ROOT = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.join(ROOT, 'site')
PREFIX = 'wiki__develop__api-v2__'

IDENT = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*$')
HDR0 = ('名称', '参数', '属性', '字段', '错误码', 'Code', '值')
HDR1 = ('类型', '值', '描述', '说明', '含义', '必填')
SKIP_HEADINGS = ('概述', '说明', '请求', '响应', '注意', '举例', '示例', '目录', '背景和目的',
                 '接口频率限制', '鉴权说明', '权限要求', '安全和授权', '安全凭证', '验证签名',
                 '签名校验', '回调地址及事件监听配置', 'WebSocket方式', '分片连接LoadBalance',
                 '事件订阅Intents', '发起连接到 Gateway', '登录鉴权获得 Session', '发送心跳 Ack',
                 '恢复登录态 Session', '通用数据结构 Payload', 'OpCode 含义', '获取访问凭证')


def page_text(path):
    s = open(path, encoding='utf-8').read()
    m = (re.search(r'<div class="theme-default-content content__default">(.*?)<footer', s, re.S)
         or re.search(r'<main[^>]*>(.*)</main>', s, re.S))
    if not m:
        return [], []
    b = re.sub(r'<(script|style)[^>]*>.*?</\1>', '', m.group(1), flags=re.S)
    pres = []

    def stash(mm):
        pres.append(html.unescape(re.sub(r'<[^>]+>', '', mm.group(1))).strip())
        return '\x00PRE%d\x00' % (len(pres) - 1)

    b = re.sub(r'<pre[^>]*>(.*?)</pre>', stash, b, flags=re.S)
    b = re.sub(r'<br\s*/?>', '\n', b)
    b = re.sub(r'</(td|th)>', '\t', b)
    b = re.sub(r'</(tr|p|div|li|h\d|code|table|ul|ol|strong|em)>', '\n', b)
    b = re.sub(r'<[^>]+>', '', b)
    b = html.unescape(b)
    lines = []
    for l in b.splitlines():
        l = re.sub(r'\t+', '\t', l.strip()).strip('\t').strip()
        if l:
            lines.append(l)
    return lines, pres


def is_header(line):
    cells = [c.strip() for c in line.split('\t')]
    if len(cells) < 2:
        return False
    return cells[0] in HDR0 and any(h in cells[1] for h in HDR1)


def parse_rows(rows):
    out = []
    for r in rows:
        c = [x.strip() for x in r.split('\t')]
        c += [''] * (4 - len(c))
        name = c[0]
        if not name or name in HDR0:
            continue
        out.append({'name': name, 'type': c[1], 'required': c[2] in ('是', '必填', 'true'),
                    'desc': c[3] if len(c) > 3 else ''})
    return out


def read_table(lines, i):
    """lines[i] may be a header row. Return (fields, next_i) or ([], i) when no table here."""
    n = len(lines)
    if i < n and is_header(lines[i]):
        i += 1
    rows = []
    while i < n and '\t' in lines[i] and not is_header(lines[i]):
        rows.append(lines[i])
        i += 1
    if not rows:
        return [], i
    return parse_rows(rows), i


def normalize_type(t):
    t = (t or '').strip()
    t = re.sub(r'\s*\(.*?\)\s*$', '', t)
    if t.startswith('[]'):
        return {'kind': 'array', 'item': normalize_type(t[2:])}
    m = re.match(r'^(.+)\[\]$', t)
    if m:
        return {'kind': 'array', 'item': normalize_type(m.group(1))}
    m = re.match(r'^(?:map|Map|object)<\s*(\w+)\s*,\s*(.+)>$', t)
    if m:
        return {'kind': 'map', 'key': normalize_type(m.group(1)), 'value': normalize_type(m.group(2))}
    low = t.lower()
    if low in ('string', 'str'):
        return {'kind': 'string'}
    if low in ('integer', 'int', 'int64', 'int32', 'long', 'number', 'float', 'double'):
        return {'kind': 'integer' if low in ('integer', 'int', 'int64', 'int32', 'long') else 'number'}
    if low in ('boolean', 'bool'):
        return {'kind': 'boolean'}
    if low in ('object', 'any', 'json', 'struct', ''):
        return {'kind': 'object'}
    return {'kind': 'ref', 'name': t}


def typed(fields):
    out = []
    for f in fields:
        f = dict(f)
        f['t'] = normalize_type(f['type'])
        out.append(f)
    return out


def split_sections(lines):
    secs = []
    cur = {'heading': '', 'lines': []}
    for l in lines:
        m = re.match(r'^(#{1,4})\s+(.*)$', l)
        if m and m.group(2).strip():
            secs.append(cur)
            cur = {'heading': m.group(2).strip(), 'lines': [], 'level': len(m.group(1))}
        else:
            cur['lines'].append(l)
    secs.append(cur)
    return secs


def parse_tables(lines):
    """Parse interleaved field-tables + nested `Type` blocks. Return (fields, types{name: fields})."""
    fields = []
    types = collections.OrderedDict()
    i = 0
    n = len(lines)
    while i < n:
        l = lines[i]
        if l.startswith('#'):
            break
        if l.startswith('\x00PRE') or l.startswith('```'):
            i += 1
            continue
        if '\t' not in l:
            if IDENT.match(l) and l not in ('null', 'true', 'false') and not l.isupper():
                tf, j = read_table(lines, i + 1)
                if tf:
                    types.setdefault(l, typed(tf))
                    i = j
                    continue
            i += 1
            continue
        if is_header(l):
            rows, j = read_table(lines, i)
            fields.extend(typed(rows))
            i = j
            continue
        rows, j = read_table(lines, i)
        if rows:
            fields.extend(typed(rows))
            i = j
        else:
            i += 1
    return fields, types


METHOD_RE = re.compile(r'^(GET|POST|PUT|PATCH|DELETE)\s+(?:https?://[A-Za-z0-9.\-]+)?(/[^\s"）)]*)\s*$')


def endpoint_chunks(lines):
    """Split a page into per-endpoint chunks: handwritten channel docs list one `METHOD /path` per API,
    and some pages (dms, forum, schedule) document several APIs."""
    marks = [(i, m.group(1), m.group(2)) for i, l in enumerate(lines)
             for m in [METHOD_RE.match(l)] if m]
    if not marks:
        return []
    chunks = []
    for n, (idx, method, path) in enumerate(marks):
        end = marks[n + 1][0] if n + 1 < len(marks) else len(lines)
        chunks.append({'method': method, 'path': path, 'lines': lines[idx:end]})
    return chunks


def parse_endpoint_chunk(chunk):
    rec = {'method': chunk['method'], 'path': chunk['path'], 'title': '', 'desc': '',
           'info': {}, 'pathParams': [], 'queryParams': [], 'body': [], 'response': [],
           'types': {}, 'examples': []}
    for sec in split_sections(chunk['lines']):
        h, ls = sec['heading'], sec['lines']
        if not rec['title'] and h and not h.startswith('\x00'):
            rec['title'] = h
        f, t = parse_tables(ls)
        rec['types'].update(t)
        hl = h.lower()
        if any(k in h for k in ('路径参数',)) or 'pathtype' in hl:
            rec['pathParams'].extend(f)
        elif '查询参数' in h or h in ('Query', 'query 参数'):
            rec['queryParams'].extend(f)
        elif any(k in h for k in ('请求体', '请求参数', '通用参数', '专有参数', '参数说明')) or h in ('Body', 'body'):
            rec['body'].extend(f)
        elif any(k in h for k in ('响应', '返回')) and '示例' not in h:
            rec['response'].extend(f)
        elif is_kv_table(ls):
            for l in ls:
                if '\t' in l:
                    k, _, v = l.partition('\t')
                    rec['info'][k.strip()] = v.strip()
    # path parameters are implied by the {placeholders} in the path
    rec['pathVars'] = re.findall(r'\{(\w+)}', rec['path'])
    return rec


def is_kv_table(lines):
    n = sum(1 for l in lines if '\t' in l)
    return n > 0 and all(len(l.split('\t')) <= 3 for l in lines if '\t' in l) and n >= 2


def parse_page(path):
    lines, pres = page_text(path)
    if not lines:
        return None
    rel = os.path.basename(path)
    if rel.startswith(PREFIX):
        rel = rel[len(PREFIX):]
    rec = {'file': rel.replace('__', '/'), 'title': '', 'desc': '', 'info': {},
           'pathParams': [], 'queryParams': [], 'headerParams': [],
           'body': [], 'response': [], 'errorCodes': [], 'examples': [], 'types': {}}
    if lines and lines[0].startswith('# '):
        rec['title'] = lines[0][2:].strip()
    else:
        rec['title'] = rec['file']
    d = []
    for l in lines[1:]:
        if l.startswith('#'):
            break
        d.append(l)
    rec['desc'] = '\n'.join(d).strip()

    for sec in split_sections(lines):
        h = sec['heading']
        ls = sec['lines']
        for i, l in enumerate(ls):
            m = re.match(r'^\x00PRE(\d+)\x00(.*)$', l)
            if m:
                rec['examples'].append({'heading': h, 'raw': pres[int(m.group(1))]})
        if h in ('基础信息', '基本信息', '事件', '基本', '请求参数', '接口信息'):
            i = 0
            if i < len(ls) and is_header(ls[i]):
                i += 1
            while i < len(ls):
                if '\t' in ls[i]:
                    k, _, v = ls[i].partition('\t')
                    rec['info'][k.strip()] = v.strip()
                i += 1
            continue
        if h in ('路径参数',):
            rec['pathParams'], _ = parse_tables(ls)
            continue
        if h in ('查询参数',):
            rec['queryParams'], _ = parse_tables(ls)
            continue
        if h in ('Header 参数', '请求头', 'Header参数'):
            rec['headerParams'], _ = parse_tables(ls)
            continue
        if h in ('请求体', '请求参数', '事件体', '消息体'):
            f, t = parse_tables(ls)
            key = 'body'
            rec[key].extend(f)
            rec['types'].update(t)
            continue
        if h in ('响应体', '返回参数', '成功响应', '响应参数'):
            f, t = parse_tables(ls)
            rec['response'].extend(f)
            rec['types'].update(t)
            continue
        if h in ('错误码', '业务错误码', '返回码'):
            f, t = parse_tables(ls)
            rec['errorCodes'].extend(f)
            continue
        if h in SKIP_HEADINGS or '示例' in h:
            continue
        f, t = parse_tables(ls)
        if f or t:
            name = h if IDENT.match(h) else re.sub(r'[^A-Za-z0-9]', '', h) or rec['types'].get('_')
            key = name or 'section'
            if f:
                rec['types'].setdefault(key, f)
            rec['types'].update(t)
    rec['endpointChunks'] = [parse_endpoint_chunk(c) for c in endpoint_chunks(expand_pre(lines, pres))]
    return rec


def expand_pre(lines, pres):
    """Inline code-block content: handwritten channel docs put `POST /path` inside <pre>."""
    out = []
    for l in lines:
        m = re.match(r'^\x00PRE(\d+)\x00?(.*)$', l)
        if m:
            raw = pres[int(m.group(1))]
            out.extend(x.strip() for x in raw.splitlines() if x.strip())
            if m.group(2).strip():
                out.append(m.group(2).strip())
        else:
            out.append(l)
    return out


METHODS = ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')


def norm_path(p):
    p = (p or '').strip().split('?')[0].split('#')[0].strip()
    if p.startswith('http'):
        parts = p.split('/', 3)
        p = '/' + parts[3] if len(parts) > 3 else '/'
    p = p.rstrip('/')
    return p if p.startswith('/') else None


def main():
    pages = {}
    for p in sorted(glob.glob(os.path.join(SITE, '*.html'))):
        r = parse_page(p)
        if r:
            pages[r['file']] = r

    endpoints = {}

    def add(ep, src):
        path = norm_path(ep.get('path') or ep.get('info', {}).get('HTTP URL'))
        method = (ep.get('method') or ep.get('info', {}).get('HTTP Method') or '').upper()
        if method not in METHODS or not path:
            return
        key = method + ' ' + path
        ep = dict(ep)
        ep.update({'key': key, 'method': method, 'path': path, 'file': src})
        ep.setdefault('types', {})
        ep.setdefault('examples', [])
        prev = endpoints.get(key)
        if prev is None:
            endpoints[key] = ep
            return
        for f in ('pathParams', 'queryParams', 'body', 'response'):
            if len(ep.get(f) or []) > len(prev.get(f) or []):
                prev[f] = ep[f]
        for k, v in ep['types'].items():
            prev['types'].setdefault(k, v)
        if not prev.get('title'):
            prev['title'] = ep.get('title')
        if not prev.get('desc'):
            prev['desc'] = ep.get('desc')
        if not prev.get('examples'):
            prev['examples'] = ep.get('examples')
        prev['sources'] = sorted(set(prev.get('sources', []) + [src] + ([ep.get('file')] if False else [])))
        prev.setdefault('sources', []).append(ep.get('file'))

    for r in pages.values():
        if r['info'].get('HTTP URL'):
            add({'method': r['info'].get('HTTP Method'), 'path': r['info']['HTTP URL'],
                 'title': r['title'], 'desc': r['desc'], 'info': r['info'],
                 'pathParams': r['pathParams'], 'queryParams': r['queryParams'],
                 'body': r['body'], 'response': r['response'], 'types': r['types'],
                 'examples': r['examples']}, r['file'])
        for c in r.get('endpointChunks') or []:
            c = dict(c)
            c['examples'] = r.get('examples')
            add(c, r['file'])

    events = [r for r in pages.values() if r['info'].get('事件名')]
    registry = collections.defaultdict(list)
    for r in pages.values():
        for name, fields in r['types'].items():
            sig = tuple((f['name'], f['type']) for f in fields)
            for prev in registry[name]:
                if prev[0] == sig:
                    break
            else:
                registry[name].append((sig, fields))
        for e in r.get('endpointChunks') or []:
            for name, fields in e['types'].items():
                sig = tuple((f['name'], f['type']) for f in fields)
                for prev in registry[name]:
                    if prev[0] == sig:
                        break
                else:
                    registry[name].append((sig, fields))

    eps = sorted(endpoints.values(), key=lambda e: e['key'])
    spec = {'counts': {'pages': len(pages), 'endpoints': len(eps), 'events': len(events),
                       'types': len(registry)},
            'endpoints': eps,
            'events': sorted(events, key=lambda r: r['info'].get('事件名', '')),
            'types': {k: v[0][1] for k, v in registry.items()}}
    json.dump(spec, open(os.path.join(ROOT, 'spec.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print('pages=%d endpoints=%d events=%d types=%d' % (len(pages), len(eps), len(events), len(registry)))

    io_summary = open(os.path.join(ROOT, 'summary.txt'), 'w', encoding='utf-8')
    io_summary.write('--- ENDPOINTS (%d) ---\n' % len(eps))
    for r in eps:
        io_summary.write('%-6s %-56s %-24s pp=%d qp=%d body=%d resp=%d types=%s | %s\n' % (
            r['method'], r['path'], (r.get('title') or '')[:22], len(r.get('pathParams') or []),
            len(r.get('queryParams') or []), len(r.get('body') or []), len(r.get('response') or []),
            ','.join(list(r.get('types', {}))[:4]), r.get('file', '')))
    io_summary.write('\n--- EVENTS ---\n')
    for r in spec['events']:
        io_summary.write('%-26s %-30s %-20s body=%d types=%s\n' % (
            r['info'].get('事件名', '?'), r['info'].get('Intent', '?'), r['title'][:18],
            len(r['body']), ','.join(list(r['types'])[:6])))
    io_summary.write('\n--- TYPE REGISTRY (%d) ---\n' % len(spec['types']))
    for k in sorted(spec['types']):
        io_summary.write('%-26s %s\n' % (k, ', '.join(
            '%s:%s' % (f['name'], f['type']) for f in spec['types'][k])))
    io_summary.close()

    fixtures = os.path.join(ROOT, 'fixtures')
    os.makedirs(fixtures, exist_ok=True)
    n = 0
    for r in spec['events']:
        name = r['info'].get('事件名', '')
        for i, ex in enumerate(r.get('examples') or []):
            raw = ex['raw']
            start = raw.find('{')
            if start >= 0 and 'C2C' in (name + raw).upper() or start >= 0:
                json.dump({'event': name, 'index': i, 'raw': raw[start:]},
                          open(os.path.join(fixtures, 'event_%s_%d.json' % (name.lower() or r['file'][:24], i)),
                               'w', encoding='utf-8'), ensure_ascii=False, indent=1)
                n += 1
                break
    print('event fixtures=%d' % n)
    print('summary written')


if __name__ == '__main__':
    main()
