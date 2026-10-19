"""Prints the comparison of hierarchical runs (train2.py): function and fine accuracy on each held-out set, size and time, then
per-function and per-class accuracy of one chosen run.

    python compare2.py runs2/small runs2/rf runs2/small2h --detail runs2/small2h
"""
import argparse, json, os
from dataset import DATA


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('runs', nargs='+'); ap.add_argument('--detail')
    args = ap.parse_args()
    sets = ['synth_val', 'harvest_holdout', 'saves_holdout']
    print('| run | params | weights | time | ' + ' | '.join(f'{s}: function / class' for s in sets) + ' |')
    for run in args.runs:
        m = json.load(open(os.path.join(DATA, run, 'metrics.json'), encoding='utf-8'))
        cells = [f"{m['final'][s]['function']:.1%} / {m['final'][s]['fine']:.1%}" for s in sets if s in m['final']]
        print(f"| {os.path.basename(run)} | {m['params']} | {m['weights_bytes'] // 1024} KB | {m['seconds'] / 60:.1f} min | " + ' | '.join(cells) + ' |')
    if not args.detail: return
    m = json.load(open(os.path.join(DATA, args.detail, 'metrics.json'), encoding='utf-8'))
    classes = json.load(open(os.path.join(DATA, 'corpus', 'classes.json'), encoding='utf-8'))
    print('\nper function (held-out saves | held-out seeds):')
    for i, f in enumerate(m['functions']):
        a = m['final']['saves_holdout']['per_function'][i]; b = m['final']['harvest_holdout']['per_function'][i]
        if a[1] or b[1]: print(f"  {f:22s} {a[0]}/{a[1]} | {b[0]}/{b[1]}")
    print('\nper class (held-out saves | held-out seeds), classes with held-out samples:')
    for i, k in enumerate(m['classes']):
        a = m['final']['saves_holdout']['per_fine'][i]; b = m['final']['harvest_holdout']['per_fine'][i]
        if a[1] or b[1]: print(f"  {k:17s} {classes['fine_names'][i]:24s} {a[0]}/{a[1]} | {b[0]}/{b[1]}")
    print('\nfunction confusion on held-out saves (rows truth, columns prediction):', m['functions'])
    for f, row in zip(m['functions'], m['final']['saves_holdout']['function_confusion']):
        if sum(row): print(f'  {f:22s} {row}')
    if 'disputed' in m['final']: print('disputed:', m['final']['disputed']['function'], m['final']['disputed']['function_n'])


if __name__ == '__main__':
    main()
