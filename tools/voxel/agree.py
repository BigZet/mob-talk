"""Reconciles the rule labels with the vision model's answers and clusters the model's free descriptions.

Per cube: a sure rule wins; where the rule is not sure, the model's function counts when both views ('a' and 'b') agree on it
with confidence ≥ 0.7 (then the cube gets a function label without a fine class); where a sure rule and a sure, consistent model
disagree on the function, the cube is 'disputed' and leaves training. Registry labels (harvested pieces, background) are never
overridden, only compared. Writes vlm/agreed.json (id -> label override) and vlm/clusters.json (TF-IDF + k-means over the
descriptions, numpy only), and prints the statistics.

    python agree.py --k 24
"""
import argparse, collections, json, math, os, re
import numpy as np
from dataset import DATA, BlockTable
import labels, vlm

STOP = set('и в во на с со по из к у о об от до за для не что это как его её их он она оно они там тут здесь же ли бы а но или то так '
           'также очень есть где который которая которое которые этот эта эти этом этой видно видны виден видна рядом вокруг около над под '
           'между внутри снаружи сверху снизу существо существа существом стоит находится место месте куб блоков блоки блок minecraft '
           'красный столбик небольшой небольшая небольшое большой большая большое'.split())


def tokens(text):
    words = re.findall(r'[а-яёa-z]+', text.lower())
    return [w[:6] for w in words if len(w) > 2 and w not in STOP]  # a crude stem: the first six letters


def tfidf_kmeans(texts, k, rng, iters=60):
    docs = [tokens(t) for t in texts]
    df = collections.Counter(w for d in docs for w in set(d))
    vocab = [w for w, c in df.items() if c >= 3]
    index = {w: i for i, w in enumerate(vocab)}
    X = np.zeros((len(docs), len(vocab)), dtype=np.float32)
    for i, d in enumerate(docs):
        for w, c in collections.Counter(d).items():
            if w in index: X[i, index[w]] = (1 + math.log(c)) * math.log(len(docs) / df[w])
    X /= np.linalg.norm(X, axis=1, keepdims=True).clip(1e-6)
    # k-means++ seeding, cosine (unit vectors)
    centres = [X[rng.integers(len(X))]]
    for _ in range(1, k):
        d = 1 - np.max(X @ np.stack(centres).T, axis=1)
        p = d.clip(0) ** 2; p /= p.sum()
        centres.append(X[rng.choice(len(X), p=p)])
    C = np.stack(centres)
    for _ in range(iters):
        assign = np.argmax(X @ C.T, axis=1)
        new = np.stack([X[assign == j].mean(axis=0) if (assign == j).any() else C[j] for j in range(k)])
        new /= np.linalg.norm(new, axis=1, keepdims=True).clip(1e-6)
        if np.allclose(new, C): break
        C = new
    assign = np.argmax(X @ C.T, axis=1)
    return assign, C, vocab


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--k', type=int, default=24); ap.add_argument('--min-conf', type=float, default=0.7)
    args = ap.parse_args()
    vlm.TABLE = BlockTable()
    answers = collections.defaultdict(dict)
    for line in open(vlm.CACHE, encoding='utf-8'):
        a = json.loads(line)
        if a.get('answer') and not a['id'].endswith('#test'): answers[a['id']][a['views']] = a['answer']
    data = vlm.load_all([os.path.join(DATA, 'harvest'), os.path.join(DATA, 'saves')])
    out, stats = {}, collections.Counter()
    registry_rows = collections.Counter()
    for sid, views in answers.items():
        if sid not in data: continue
        cube, blocks, meta = data[sid]
        a, b = views.get('a'), views.get('b')
        sure = a and b and a['function'] == b['function'] and min(a['confidence'], b['confidence']) >= args.min_conf and a['function'] in labels.FUNCTIONS
        truth = labels.fine_of(meta)
        if meta.get('kind') == 'build':
            rule = meta.get('rule', 'unknown')
            if rule != 'unknown':
                if sure and a['function'] != labels.function_of(rule):
                    out[sid] = dict(status='disputed', rule=rule, vlm=a['function']); stats['build: disputed'] += 1
                else:
                    stats['build: rule' + (' + VLM agrees' if sure else '')] += 1
            elif sure:
                out[sid] = dict(status='vlm', fine=None, function=a['function']); stats['build: VLM label'] += 1
            else:
                stats['build: unknown, VLM unsure'] += 1
        elif truth is None:
            if sure: out[sid] = dict(status='vlm', fine=None, function=a['function']); stats['unlabelled harvest: VLM label'] += 1
            else: stats['unlabelled harvest: VLM unsure'] += 1
        else:
            got = (a or b or {}).get('function')
            want = labels.function_of(truth)
            registry_rows[(want, got)] += 1
            stats['registry: VLM agrees' if got == want else 'registry: VLM differs'] += 1
    os.makedirs(os.path.join(DATA, 'vlm'), exist_ok=True)
    json.dump(out, open(os.path.join(DATA, 'vlm', 'agreed.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(json.dumps(stats, ensure_ascii=False, indent=1))
    print('registry function vs VLM function (top pairs):')
    for (want, got), n in registry_rows.most_common(25): print(f'  {n:5d}  {want} -> {got}')
    # Clusters of free descriptions.
    items = [(sid, v['class'] + '. ' + v['description']) for sid, views in answers.items() for k_, v in views.items() if k_ == 'a' and sid in data]
    rng = np.random.default_rng(5)
    assign, C, vocab = tfidf_kmeans([t for _, t in items], args.k, rng)
    clusters = []
    for j in range(args.k):
        members = [i for i in range(len(items)) if assign[i] == j]
        if not members: continue
        top = [vocab[t] for t in np.argsort(-C[j])[:8]]
        truths = collections.Counter(labels.fine_of(data[items[i][0]][2]) or data[items[i][0]][2].get('rule') or '?' for i in members)
        classes = collections.Counter(answers[items[i][0]]['a']['class'].lower() for i in members)
        clusters.append(dict(size=len(members), terms=top, vlm_classes=classes.most_common(6), sources=truths.most_common(6),
                             examples=[items[i][1] for i in members[:4]]))
    clusters.sort(key=lambda c: -c['size'])
    json.dump(clusters, open(os.path.join(DATA, 'vlm', 'clusters.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    for c in clusters:
        print(f"[{c['size']}] {' '.join(c['terms'])} | VLM: {c['vlm_classes'][:3]} | truth: {c['sources'][:3]}")


if __name__ == '__main__':
    main()
