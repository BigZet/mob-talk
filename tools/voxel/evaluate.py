"""Compares trained runs on the clean validation set and on two harder views of it: every cube turned by a random quarter
turn (what a model without augmentation never saw) and every cube with a few percent of cells replaced by random
materials (clutter: torches, dropped sand, a stray block). Prints one row per run and writes compare.json.

    python evaluate.py --val val.mtvx --runs runs/small-plain runs/small-aug ...
"""
import argparse, json, os
import numpy as np
import torch
from train import read_dataset, Net, ARCHS, one_hot, evaluate


def rotated(cubes, rng):
    out = np.empty_like(cubes)
    for i in range(len(cubes)):
        k = int(rng.integers(1, 4))
        out[i] = np.rot90(cubes[i], k, axes=(0, 2))
    return out


def noisy(cubes, rng, fraction, materials):
    out = cubes.copy()
    n = out.shape[1] ** 3
    count = int(n * fraction)
    for i in range(len(out)):
        idx = rng.choice(n, count, replace=False)
        flat = out[i].reshape(-1)
        flat[idx] = rng.integers(0, materials, count)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--val', required=True); ap.add_argument('--runs', nargs='+', required=True); ap.add_argument('--noise', type=float, default=0.03)
    args = ap.parse_args()
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    cubes, labels, names, materials = read_dataset(args.val)
    rng = np.random.default_rng(5)
    views = {'clean': cubes, 'rotated': rotated(cubes, rng), f'noise{int(args.noise * 100)}%': noisy(cubes, rng, args.noise, materials)}
    rows = []
    for run in args.runs:
        metrics = json.load(open(os.path.join(run, 'metrics.json')))
        model = Net(materials, len(names), **ARCHS[metrics['arch']]).to(device)
        model.load_state_dict(torch.load(os.path.join(run, 'best.pt'), map_location=device))
        row = dict(run=os.path.basename(run), arch=metrics['arch'], aug=metrics['aug'], train=metrics['train'], params=metrics['params'],
                   weights_kb=round(metrics['weights_bytes'] / 1024), seconds=round(metrics['seconds']))
        for name, view in views.items():
            acc, per_class, _ = evaluate(model, view, labels, materials, device, len(names))
            row[name] = round(float(acc), 4); row[name + '_per_class'] = dict(zip(names, [round(p, 3) for p in per_class]))
        rows.append(row)
        print(f"{row['run']:16s} {row['arch']:6s} aug={row['aug']!s:5s} train={row['train']:6d} params={row['params']:7d} {row['weights_kb']:5d} KB "
              f"{row['seconds']:4d}s  clean {row['clean']:.4f}  rotated {row['rotated']:.4f}  noise {row[list(views)[2]]:.4f}", flush=True)
    json.dump(rows, open(os.path.join(os.path.dirname(args.runs[0].rstrip('/\\')), 'compare.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
