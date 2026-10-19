"""Builds the training corpus of the hierarchical classifier from every source, with held-out sets that never share a world:

  train            generated scenes (train.mtvx), harvested cubes of the training seeds and cubes from the training saves, the real
                   ones multiplied by mutations (mutate.py); VLM-only labels give a function without a fine class
  synth_val        generated scenes of another seed
  harvest_holdout  harvested cubes of held-out seeds (unmutated)
  saves_holdout    cubes from held-out saves (unmutated)
  disputed         cubes where the rule and the VLM disagree: kept aside, never trained on

Writes <out>/<set>.npz with mat, fam (uint8 [n,16,16,16]), fine (int16, -1 none), func (int8, -1 none), src (source names), and
<out>/classes.json (fine classes kept, their Russian names and functions; counts per source).

    python assemble.py --holdout-seeds 2 --holdout-saves "stream 1" "Новый мир (1)" "New World (19)" --mutations 12
"""
import argparse, collections, json, os
import numpy as np
from dataset import DATA, bases, read_v2, read_header, BlockTable, families_for, CUBE
import labels, mutate


def read_synth(path):
    with open(path, 'rb') as f:
        version, count, cube, materials, names = read_header(f)
        raw = np.fromfile(f, dtype=np.uint8, count=count * (cube ** 3 + 1)).reshape(count, cube ** 3 + 1)
    fam = np.fromfile(path + '.fam', dtype=np.uint8, count=count * cube ** 3).reshape(count, cube, cube, cube)
    mat = raw[:, :-1].reshape(count, cube, cube, cube)
    fine = [labels.SYNTHETIC[names[i]] for i in raw[:, -1]]
    return mat, fam, fine


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=os.path.join(DATA, 'corpus'))
    ap.add_argument('--synth', type=int, default=60000)
    ap.add_argument('--holdout-seeds', type=int, default=2, help='the last N harvested worlds are held out')
    ap.add_argument('--holdout-saves', nargs='*', default=['stream 1', 'Новый мир (1)', 'New World (19)'])
    ap.add_argument('--mutations', type=int, default=12)
    ap.add_argument('--harvest', nargs='*', default=['harvest', 'harvest2'], help='folders under the data directory with harvested worlds; '
                    'the held-out seeds are the last worlds of the first folder')
    ap.add_argument('--min-class', type=int, default=12, help='fine classes with fewer real source cubes are dropped to their function')
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    rng = np.random.default_rng(3)
    table = BlockTable()
    overrides = {}
    agreed = os.path.join(DATA, 'vlm', 'agreed.json')
    if os.path.exists(agreed): overrides = json.load(open(agreed, encoding='utf-8'))

    real = collections.defaultdict(list)  # set -> list of (mat, fam, fine, func, src)
    first = bases(os.path.join(DATA, args.harvest[0]))
    holdout_bases = set(first[-args.holdout_seeds:]) if args.holdout_seeds else set()
    harvest = first + [b for f in args.harvest[1:] if os.path.isdir(os.path.join(DATA, f)) for b in bases(os.path.join(DATA, f))]
    backgrounds = ([], [])
    for base in harvest + bases(os.path.join(DATA, 'saves')):
        cubes, blocks, meta = read_v2(base)
        fams = families_for(blocks, meta, table)
        name = os.path.relpath(base, DATA).replace('\\', '/')
        is_save = name.startswith('saves/')
        for i, m in enumerate(meta):
            sid = f'{name}#{i}'
            o = overrides.get(sid, {})
            if o.get('status') == 'disputed':
                real['disputed'].append((cubes[i], fams[i], o.get('rule'), labels.function_of(o['rule']) if o.get('rule') else None, sid)); continue
            fine = o['fine'] if 'fine' in o else labels.fine_of(m)
            func = o.get('function') or (labels.function_of(fine) if fine else None)
            if fine is None and func is None: continue
            if is_save: target = 'saves_holdout' if m.get('save') in args.holdout_saves else 'train_real'
            else: target = 'harvest_holdout' if base in holdout_bases else 'train_real'
            real[target].append((cubes[i], fams[i], fine, func, sid))
            if target == 'train_real' and m.get('kind') == 'background' and m.get('relief') in ('surface', 'water'):
                backgrounds[0].append(cubes[i]); backgrounds[1].append(fams[i])
    counts = collections.Counter(r[2] for r in real['train_real'] if r[2])
    keep = [k for k in labels.FINE_KEYS if counts.get(k, 0) >= args.min_class or k in labels.SYNTHETIC.values()]
    fine_index = {k: i for i, k in enumerate(keep)}
    func_index = {f: i for i, f in enumerate(labels.FUNCTIONS)}

    def pack(rows, name, mutations=0):
        mats, fams, fines, funcs, srcs = [], [], [], [], []
        bg = (np.array(backgrounds[0]), np.array(backgrounds[1])) if backgrounds[0] else None
        for mat, fam, fine, func, src in rows:
            fi = fine_index.get(fine, -1) if fine else -1
            fu = func_index.get(func, -1) if func else -1
            mats.append(mat); fams.append(fam); fines.append(fi); funcs.append(fu); srcs.append(src)
            for m2, f2 in (mutate.variants(mat, fam, mutations, rng, bg) if mutations else []):
                mats.append(m2); fams.append(f2); fines.append(fi); funcs.append(fu); srcs.append(src + '~m')
        if not mats: return 0
        np.savez(os.path.join(args.out, name + '.npz'), mat=np.stack(mats).astype(np.uint8), fam=np.stack(fams).astype(np.uint8),
                 fine=np.array(fines, dtype=np.int16), func=np.array(funcs, dtype=np.int8), src=np.array(srcs))
        return len(mats)

    smat, sfam, sfine = read_synth(os.path.join(DATA, 'synth', 'train.mtvx'))
    n = min(args.synth, len(smat))
    synth_rows = [(smat[i], sfam[i], sfine[i], labels.function_of(sfine[i]), 'synth') for i in range(n)]
    vmat, vfam, vfine = read_synth(os.path.join(DATA, 'synth', 'val.mtvx'))
    # Real cubes are mutated; generated scenes are endless already.
    real_n = pack(real['train_real'], 'train_real', args.mutations)
    synth_n = pack(synth_rows, 'train_synth')
    sizes = dict(train_real=real_n, train_synth=synth_n,
                 synth_val=pack([(vmat[i], vfam[i], vfine[i], labels.function_of(vfine[i]), 'synth') for i in range(len(vmat))], 'synth_val'),
                 harvest_holdout=pack(real['harvest_holdout'], 'harvest_holdout'), saves_holdout=pack(real['saves_holdout'], 'saves_holdout'),
                 disputed=pack(real['disputed'], 'disputed'))
    by_source = {s: dict(collections.Counter(r[2] or ('func:' + (r[3] or '?')) for r in rows)) for s, rows in real.items()}
    json.dump(dict(fine=keep, fine_names=[labels.FINE[k][1] for k in keep], fine_functions=[labels.FINE[k][0] for k in keep], functions=labels.FUNCTIONS,
                   sizes=sizes, train_real_sources=dict(counts), by_source=by_source, holdout_harvest=sorted(holdout_bases), holdout_saves=args.holdout_saves),
              open(os.path.join(args.out, 'classes.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(json.dumps(sizes), len(keep), 'fine classes')
    for s, c in by_source.items(): print(s, sum(c.values()), sorted(c.items(), key=lambda kv: -kv[1])[:60])


if __name__ == '__main__':
    main()
