"""Trains the hierarchical place classifier on the corpus built by assemble.py: input is the 16³ cube as one-hot materials (16)
plus one-hot block families (24); outputs are a fine class (deregistered house, mineshaft, birch, ...) and the function of the place
(nature, dwelling, farming, ...). Architectures:

  small    3 stages conv3d 16/32/64 + max pool, global average, one fine head; the function is the sum of its classes' probabilities
  rf       4 stages 16/32/64/96: the last stage sees the whole cube (a larger receptive field), one fine head
  small2h  the small trunk with two heads: fine and function; function-only labels (VLM) train the function head directly

Single-head models learn from function-only labels too, through the summed probability of the function's classes.
Held-out sets share no world with training (other seeds, other saves). Writes classifier.bin (format 2, read by PlaceClassifier),
parity.bin and metrics.json into --out.

    python train2.py --arch small2h --epochs 12 --out runs2/small2h
"""
import argparse, json, os, struct, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from dataset import DATA, MATERIALS, FAMILIES

NM, NF = len(MATERIALS), len(FAMILIES)
ARCHS = {'small': ([16, 32, 64], False), 'rf': ([16, 32, 64, 96], False), 'small2h': ([16, 32, 64], True), 'rf2h': ([16, 32, 64, 96], True)}


class Net(nn.Module):
    def __init__(self, stages, two_heads, fine, functions):
        super().__init__()
        layers, cin = [], NM + NF
        for cout in stages:
            layers += [nn.Conv3d(cin, cout, 3, padding=1, bias=False), nn.BatchNorm3d(cout), nn.ReLU(inplace=True), nn.MaxPool3d(2)]
            cin = cout
        self.features = nn.Sequential(*layers)
        self.fine = nn.Linear(cin, fine)
        self.func = nn.Linear(cin, functions) if two_heads else None

    def forward(self, x):
        h = self.features(x).mean(dim=(2, 3, 4))
        return self.fine(h), (self.func(h) if self.func is not None else None)


def encode(mat, fam, device):
    m = torch.from_numpy(mat).to(device, non_blocking=True).long()
    f = torch.from_numpy(fam).to(device, non_blocking=True).long().clamp(max=NF - 1)
    return torch.cat([F.one_hot(m, NM), F.one_hot(f, NF)], dim=-1).permute(0, 4, 1, 2, 3).float()


def augment(x, noise):
    k = int(torch.randint(0, 4, (1,)))
    if k: x = torch.rot90(x, k, dims=(2, 4))
    if torch.rand(1) < 0.5: x = torch.flip(x, dims=(2,))
    if torch.rand(1) < 0.5: x = torch.flip(x, dims=(4,))
    if noise > 0:
        n = x.shape[0]
        frac = torch.rand(n, 1, 1, 1, 1, device=x.device) * noise
        mask = torch.rand(n, 1, *x.shape[2:], device=x.device) < frac
        rm = F.one_hot(torch.randint(0, NM, (n, *x.shape[2:]), device=x.device), NM)
        rf = F.one_hot(torch.randint(0, NF, (n, *x.shape[2:]), device=x.device), NF)
        r = torch.cat([rm, rf], dim=-1).permute(0, 4, 1, 2, 3).float()
        x = torch.where(mask, r, x)
    return x


def function_logits(fine_logits, func_logits, parent, nfunc):
    """Log-probabilities of functions: from the function head, or summed from the fine classes."""
    if func_logits is not None: return F.log_softmax(func_logits, dim=1)
    p = F.softmax(fine_logits, dim=1)
    agg = torch.zeros(p.shape[0], nfunc, device=p.device).index_add_(1, parent, p)
    return torch.log(agg.clamp_min(1e-8))


CORPUS = os.path.join(DATA, 'corpus')


def load(name):
    path = os.path.join(CORPUS, name + '.npz')
    if not os.path.exists(path): return None
    z = np.load(path)
    return dict(mat=z['mat'], fam=z['fam'], fine=z['fine'].astype(np.int64), func=z['func'].astype(np.int64), src=z['src'])


def evaluate(model, data, parent, nfunc, nfine, device, batch=512):
    model.eval()
    fine_ok = fine_n = func_ok = func_n = 0
    per_fine = np.zeros((nfine, 2), dtype=np.int64); per_func = np.zeros((nfunc, 2), dtype=np.int64)
    confusion = np.zeros((nfunc, nfunc), dtype=np.int64)
    with torch.no_grad():
        for i in range(0, len(data['mat']), batch):
            x = encode(data['mat'][i:i + batch], data['fam'][i:i + batch], device)
            fl, gl = model(x)
            pf = fl.argmax(1).cpu().numpy()
            pg = function_logits(fl, gl, parent, nfunc).argmax(1).cpu().numpy()
            yf, yg = data['fine'][i:i + batch], data['func'][i:i + batch]
            for a, b in zip(yf, pf):
                if a < 0: continue
                fine_n += 1; fine_ok += a == b; per_fine[a, 0] += a == b; per_fine[a, 1] += 1
            for a, b in zip(yg, pg):
                if a < 0: continue
                func_n += 1; func_ok += a == b; per_func[a, 0] += a == b; per_func[a, 1] += 1; confusion[a, b] += 1
    return dict(fine=fine_ok / max(1, fine_n), fine_n=int(fine_n), function=func_ok / max(1, func_n), function_n=int(func_n),
                per_fine=per_fine.tolist(), per_function=per_func.tolist(), function_confusion=confusion.tolist())


def fold_bn(conv, bn):
    w = conv.weight.detach().cpu().clone()
    scale = bn.weight.detach().cpu() / torch.sqrt(bn.running_var.detach().cpu() + bn.eps)
    b = bn.bias.detach().cpu() - bn.running_mean.detach().cpu() * scale
    return (w * scale.reshape(-1, 1, 1, 1, 1)).numpy().astype(np.float32), b.numpy().astype(np.float32)


def utf(f, s):
    raw = s.encode('utf-8'); f.write(struct.pack('>H', len(raw))); f.write(raw)


def export(model, classes, path):
    """Format 2: 'MTCL', version 2, materials, families, cube; function names; layers (1 conv, 2 pool, 3 gap); heads (name, labels,
    parent function per label for the fine head, linear weights). Big-endian float32."""
    model = model.cpu().eval()
    mods = list(model.features)
    layers, i = [], 0
    while i < len(mods):
        if isinstance(mods[i], nn.Conv3d): layers.append(('conv',) + fold_bn(mods[i], mods[i + 1])); i += 3
        elif isinstance(mods[i], nn.MaxPool3d): layers.append(('pool',)); i += 1
        else: raise ValueError(mods[i])
    layers.append(('gap',))
    functions = classes['functions']
    with open(path, 'wb') as f:
        f.write(b'MTCL'); f.write(struct.pack('>iiii', 2, NM, NF, 16))
        f.write(struct.pack('>i', len(functions)))
        for n in functions: utf(f, n)
        f.write(struct.pack('>i', len(layers)))
        for layer in layers:
            if layer[0] == 'conv':
                w, b = layer[1], layer[2]
                f.write(struct.pack('>iiiiii', 1, w.shape[1], w.shape[0], w.shape[2], 1, 1)); f.write(w.astype('>f4').tobytes()); f.write(b.astype('>f4').tobytes())
            elif layer[0] == 'pool': f.write(struct.pack('>i', 2))
            else: f.write(struct.pack('>i', 3))
        heads = [('fine', classes['fine_names'], [functions.index(x) for x in classes['fine_functions']], model.fine)]
        if model.func is not None: heads.append(('function', functions, list(range(len(functions))), model.func))
        f.write(struct.pack('>i', len(heads)))
        for name, labels_, parents, lin in heads:
            utf(f, name); f.write(struct.pack('>i', len(labels_)))
            for n, p in zip(labels_, parents): utf(f, n); f.write(struct.pack('>i', p))
            w = lin.weight.detach().numpy().astype(np.float32); b = lin.bias.detach().numpy().astype(np.float32)
            f.write(struct.pack('>ii', w.shape[1], w.shape[0])); f.write(w.astype('>f4').tobytes()); f.write(b.astype('>f4').tobytes())
    return os.path.getsize(path)


def write_parity(model, data, path, count=12):
    """Cubes (materials, families) and the PyTorch logits of every head, for the Java parity test."""
    model = model.cpu().eval()
    idx = np.linspace(0, len(data['mat']) - 1, count).astype(int)
    with torch.no_grad():
        fl, gl = model(encode(data['mat'][idx], data['fam'][idx], 'cpu'))
    with open(path, 'wb') as f:
        f.write(b'MTP2'); f.write(struct.pack('>iiii', count, 16, fl.shape[1], 0 if gl is None else gl.shape[1]))
        for j, i in enumerate(idx):
            f.write(data['mat'][i].tobytes()); f.write(data['fam'][i].tobytes())
            f.write(fl[j].numpy().astype('>f4').tobytes())
            if gl is not None: f.write(gl[j].numpy().astype('>f4').tobytes())


def main():
    global CORPUS
    ap = argparse.ArgumentParser()
    ap.add_argument('--arch', default='small2h', choices=ARCHS.keys()); ap.add_argument('--epochs', type=int, default=12)
    ap.add_argument('--batch', type=int, default=128); ap.add_argument('--lr', type=float, default=2e-3); ap.add_argument('--noise', type=float, default=0.06)
    ap.add_argument('--synth-share', type=float, default=0.35, help='share of generated scenes in each epoch')
    ap.add_argument('--steps', type=int, default=700, help='batches per epoch'); ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--out', required=True); ap.add_argument('--corpus', default=os.path.join(DATA, 'corpus'))
    args = ap.parse_args()
    CORPUS = args.corpus
    torch.manual_seed(args.seed); rng = np.random.default_rng(args.seed)
    os.makedirs(args.out, exist_ok=True)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    classes = json.load(open(os.path.join(CORPUS, 'classes.json'), encoding='utf-8'))
    nfine, nfunc = len(classes['fine']), len(classes['functions'])
    parent = torch.tensor([classes['functions'].index(x) for x in classes['fine_functions']], device=device)
    real, synth = load('train_real'), load('train_synth')
    holdouts = {k: load(k) for k in ('synth_val', 'harvest_holdout', 'saves_holdout', 'disputed')}
    stages, two = ARCHS[args.arch]
    model = Net(stages, two, nfine, nfunc).to(device)
    params = sum(p.numel() for p in model.parameters())
    # Balanced draws of real cubes: inverse square root of the class frequency (fine class, or function when there is none).
    key = np.where(real['fine'] >= 0, real['fine'], 1000 + real['func'])
    uniq, inv, cnt = np.unique(key, return_inverse=True, return_counts=True)
    w = 1.0 / np.sqrt(cnt[inv]); w /= w.sum()
    print(f'{args.arch}: params {params}, real {len(real["mat"])}, synth {len(synth["mat"])}, fine {nfine}, device {device}', flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=args.epochs * args.steps)
    started = time.time(); history = []; best = (-1, None)
    ns = int(args.batch * args.synth_share)
    for epoch in range(args.epochs):
        model.train(); loss_sum = 0
        for step in range(args.steps):
            ri = np.sort(rng.choice(len(real['mat']), args.batch - ns, p=w)); si = np.sort(rng.integers(0, len(synth['mat']), ns))
            mat = np.concatenate([real['mat'][ri], synth['mat'][si]]); fam = np.concatenate([real['fam'][ri], synth['fam'][si]])
            yf = torch.from_numpy(np.concatenate([real['fine'][ri], synth['fine'][si]])).to(device)
            yg = torch.from_numpy(np.concatenate([real['func'][ri], synth['func'][si]])).to(device)
            x = augment(encode(mat, fam, device), args.noise)
            fl, gl = model(x)
            loss = F.cross_entropy(fl, yf, ignore_index=-1) if (yf >= 0).any() else fl.sum() * 0
            if (yg >= 0).any(): loss = loss + F.nll_loss(function_logits(fl, gl, parent, nfunc), yg, ignore_index=-1)
            opt.zero_grad(set_to_none=True); loss.backward(); opt.step(); sched.step()
            loss_sum += loss.item()
        ev = {k: evaluate(model, v, parent, nfunc, nfine, device) for k, v in holdouts.items() if v is not None and k != 'disputed'}
        score = np.mean([ev[k]['function'] + ev[k]['fine'] for k in ev if k != 'synth_val'] or [0])
        history.append(dict(epoch=epoch + 1, loss=loss_sum / args.steps, seconds=time.time() - started, **{k: (round(v['function'], 4), round(v['fine'], 4)) for k, v in ev.items()}))
        print(json.dumps(history[-1], ensure_ascii=False), flush=True)
        # The held-out sets are for reporting only: the model kept is the last one (the one-cycle schedule ends converged),
        # never the epoch that happened to score best on them.
        best = (score, {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}, epoch + 1)
    final = {k: evaluate(model, v, parent, nfunc, nfine, device) for k, v in holdouts.items() if v is not None}
    size = export(model, classes, os.path.join(args.out, 'classifier.bin'))
    write_parity(model, holdouts['saves_holdout'] or holdouts['harvest_holdout'], os.path.join(args.out, 'parity.bin'))
    torch.save(best[1], os.path.join(args.out, 'best.pt'))
    json.dump(dict(arch=args.arch, params=params, weights_bytes=size, seconds=time.time() - started, best_epoch=best[2], args=vars(args),
                   final=final, history=history, classes=classes['fine'], functions=classes['functions']),
              open(os.path.join(args.out, 'metrics.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print('final', json.dumps({k: (round(v['function'], 4), round(v['fine'], 4), v['function_n'], v['fine_n']) for k, v in final.items()}), f'{size} bytes',
          f'{time.time() - started:.0f}s', flush=True)


if __name__ == '__main__':
    main()
