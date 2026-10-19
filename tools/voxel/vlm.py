"""Second opinion on places from a vision model: renders a cube as a 2×2 collage (render.py), asks an OpenRouter model for the
function of the place from a fixed list, a short class name in its own words, a confidence and a one-sentence description.
Each cube can be asked twice with different view sets ('a', 'b'); answers, token use and cost go to an append-only jsonl cache,
so a rerun never pays twice. The key comes from OPENROUTER_API_KEY or the game's DPAPI-encrypted key file and never reaches a
file, a log or a prompt.

    python vlm.py check   --n 50          # rule-confident cubes: how often the model agrees with the rules
    python vlm.py label                   # builds from saves, harvested cubes without a sure label, a sample for descriptions
"""
import argparse, base64, collections, json, os, random, subprocess, sys, threading, time, urllib.request
from concurrent.futures import ThreadPoolExecutor
import numpy as np
from dataset import DATA, bases, read_v2
import labels, render, rules

MODEL = 'openai/gpt-6-luna'
URL = 'https://openrouter.ai/api/v1/chat/completions'
CACHE = os.path.join(DATA, 'vlm', 'answers.jsonl')

PANELS = {
    'a': 'Вверху слева и вверху справа — вид сбоку-сверху с двух противоположных углов (северо-восток и юго-запад; глубокая земля под ногами срезана). '
         'Внизу слева — тот же куб сверху, всё выше головы существа срезано. Внизу справа — план на уровне ног (вид сверху, север вверху).',
    'b': 'Вверху слева и вверху справа — вид сбоку-сверху с двух других углов (юго-восток и северо-запад; глубокая земля под ногами срезана). '
         'Внизу слева — куб сверху с другой стороны, всё выше головы существа срезано. Внизу справа — вертикальный разрез через существо (вид с юга).'}

PROMPT = ('Это куб 16×16×16 блоков из Minecraft вокруг существа: красный столбик в два блока — оно стоит здесь. Цвета условные, без текстур: '
          'зелёный — трава и листва, коричневый — земля, тёмно-коричневый — брёвна, светло-коричневый — доски, серый — камень и кладка, '
          'синий — вода, оранжевый — лава, голубой — стекло, жёлтый — факелы и фонари, белый — снег или паутина. {panels} '
          'Определи, что это за место для существа: функция места из списка и короткое название своими словами (например «деревенский дом», '
          '«заброшенная шахта», «ферма с пшеницей», «пещера», «ель на склоне»). Если по картинке не понять — function «неизвестно» и низкая confidence.')

SCHEMA = {'type': 'object', 'additionalProperties': False, 'required': ['function', 'class', 'confidence', 'description'],
          'properties': {'function': {'type': 'string', 'enum': labels.VLM_FUNCTIONS}, 'class': {'type': 'string'},
                         'confidence': {'type': 'number'}, 'description': {'type': 'string'}}}


def load_key():
    key = os.environ.get('OPENROUTER_API_KEY', '').strip()
    if key: return key
    path = os.path.join(os.environ['LOCALAPPDATA'], 'MobTalk', 'openrouter-key.dpapi')
    blob = open(path, encoding='utf-8-sig').read().strip()
    script = ("$ErrorActionPreference='Stop'; Import-Module ($PSHOME+'\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1'); "
              "$s = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); ([System.Net.NetworkCredential]::new('', $s)).Password")
    out = subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', script], input=blob, capture_output=True, text=True, timeout=60)
    key = out.stdout.strip()
    if out.returncode != 0 or not key: raise SystemExit('could not decrypt the OpenRouter key')
    return key


class Asker:
    def __init__(self, budget):
        self.key = load_key()
        self.budget, self.spent, self.requests = budget, 0.0, 0
        self.lock = threading.Lock()
        self.done = {}
        os.makedirs(os.path.dirname(CACHE), exist_ok=True)
        if os.path.exists(CACHE):
            for line in open(CACHE, encoding='utf-8'):
                a = json.loads(line)
                self.done[(a['id'], a['views'])] = a
                self.spent += a.get('cost') or 0; self.requests += 1

    def ask(self, sid, views, blocks, palette):
        if (sid, views) in self.done: return self.done[(sid, views)]
        with self.lock:
            if self.spent >= self.budget: return None
        image = base64.b64encode(render.png(render.collage(blocks, palette, views))).decode()
        body = {'model': MODEL, 'temperature': 0.2, 'max_tokens': 900, 'reasoning': {'effort': 'low'}, 'usage': {'include': True},
                'response_format': {'type': 'json_schema', 'json_schema': {'name': 'place', 'strict': True, 'schema': SCHEMA}},
                'messages': [{'role': 'user', 'content': [{'type': 'text', 'text': PROMPT.format(panels=PANELS[views])},
                                                          {'type': 'image_url', 'image_url': {'url': 'data:image/png;base64,' + image}}]}]}
        req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers={'Authorization': 'Bearer ' + self.key, 'Content-Type': 'application/json',
                                                                                  'X-Title': 'Mob Talk place labels'})
        for attempt in range(4):
            try:
                with urllib.request.urlopen(req, timeout=90) as r:
                    data = json.loads(r.read())
                break
            except Exception as e:
                if attempt == 3: print(f'{sid} {views}: {type(e).__name__}', file=sys.stderr); return None
                time.sleep(2 + 4 * attempt)
        usage = data.get('usage') or {}
        try:
            answer = json.loads(data['choices'][0]['message']['content'])
        except Exception:
            answer = None
        rec = dict(id=sid, views=views, model=MODEL, answer=answer, cost=usage.get('cost'), prompt_tokens=usage.get('prompt_tokens'),
                   completion_tokens=usage.get('completion_tokens'), at=time.time())
        with self.lock:
            self.spent += rec['cost'] or 0; self.requests += 1
            self.done[(sid, views)] = rec
            with open(CACHE, 'a', encoding='utf-8') as f: f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        return rec


def load_all(folders):
    """Every v2 sample: id -> (blocks, palette, meta)."""
    out = {}
    for folder in folders:
        if not os.path.isdir(folder): continue
        for base in bases(folder):
            cubes, blocks, meta = read_v2(base)
            name = os.path.relpath(base, DATA).replace('\\', '/')
            for i, m in enumerate(meta): out[f'{name}#{i}'] = (cubes[i], blocks[i], m)
    return out


def rule_of(cube, blocks, meta):
    names = np.array(meta['palette'], dtype=object)[blocks]
    from dataset import BlockTable
    fam = TABLE.family_cube(blocks, meta['palette'])
    return rules.label(cube, fam, names)


TABLE = None


def run(asker, jobs, workers=8):
    results = {}
    with ThreadPoolExecutor(workers) as pool:
        futures = {pool.submit(asker.ask, sid, v, b, p): (sid, v) for sid, v, b, p in jobs}
        for n, f in enumerate(futures):
            results[futures[f]] = f.result()
            if n % 100 == 99: print(f'{n + 1}/{len(futures)} requests, spent ${asker.spent:.3f}', flush=True)
    return results


def main():
    global TABLE
    from dataset import BlockTable
    TABLE = BlockTable()
    ap = argparse.ArgumentParser()
    ap.add_argument('mode', choices=['check', 'label'])
    ap.add_argument('--n', type=int, default=50); ap.add_argument('--budget', type=float, default=5.0)
    ap.add_argument('--describe', type=int, default=1200, help='harvested cubes asked once, for descriptions')
    ap.add_argument('--max-requests', type=int, default=10000)
    ap.add_argument('--folders', nargs='*', default=['harvest', 'saves'])
    args = ap.parse_args()
    data = load_all([os.path.join(DATA, f) for f in args.folders])
    asker = Asker(args.budget)
    rng = random.Random(11)
    if args.mode == 'check':
        by_rule = collections.defaultdict(list)
        for sid, (cube, blocks, meta) in data.items():
            win, fired, _ = rule_of(cube, blocks, meta)
            if win != 'unknown': by_rule[win].append(sid)
        picks = []
        keys = sorted(by_rule, key=lambda k: len(by_rule[k]))
        while len(picks) < args.n and any(by_rule.values()):
            for k in keys:
                if by_rule[k] and len(picks) < args.n: picks.append((by_rule[k].pop(rng.randrange(len(by_rule[k]))), k))
        jobs = [(sid, 'a', data[sid][1], data[sid][2]['palette']) for sid, _ in picks]
        res = run(asker, jobs)
        strict = lenient = answered = 0
        rows = []
        for sid, rule in picks:
            r = res.get((sid, 'a'))
            if not r or not r['answer']: continue
            answered += 1
            want = labels.function_of(rule); got = r['answer']['function']
            strict += got == want
            lenient += got == want or got == 'ванильная структура' and data[sid][2].get('kind') == 'structure'
            rows.append(dict(id=sid, rule=rule, rule_function=want, vlm=r['answer'], truth=labels.fine_of(data[sid][2])))
        summary = dict(asked=len(picks), answered=answered, agree_strict=strict, agree_lenient=lenient, spent=asker.spent, requests=asker.requests,
                       by_rule=collections.Counter(k for _, k in picks))
        json.dump(dict(summary=summary, rows=rows), open(os.path.join(DATA, 'vlm', 'check.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
        print(json.dumps(summary, ensure_ascii=False))
        return
    # label: builds from saves (both views), harvested cubes without a sure label (both views), a described sample (one view)
    both, once = [], []
    for sid, (cube, blocks, meta) in data.items():
        if meta.get('src') == 'save' or labels.fine_of(meta) is None: both.append(sid)
    harvested = [sid for sid, (_, _, m) in data.items() if m.get('src') == 'harvest' and labels.fine_of(m) is not None]
    rng.shuffle(harvested)
    once = harvested[:args.describe]
    jobs = [(sid, v, data[sid][1], data[sid][2]['palette']) for sid in both for v in ('a', 'b')] + [(sid, 'a', data[sid][1], data[sid][2]['palette']) for sid in once]
    jobs = jobs[:max(0, args.max_requests - asker.requests)]
    print(f'{len(both)} cubes twice, {len(once)} once: {len(jobs)} requests (already {asker.requests}, ${asker.spent:.3f})', flush=True)
    run(asker, jobs)
    print(f'done: {asker.requests} requests, ${asker.spent:.3f}')


if __name__ == '__main__':
    main()
