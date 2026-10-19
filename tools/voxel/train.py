"""Trains the place classifier: a small 3D CNN over 16³ cubes of material ids (one channel per material) that names the kind
of place a creature stands in (pit, chamber, funnel, cage, house, farm, mine, trap, open, pillar, pond). The dataset comes
from the Java generator (SceneDataset), so Java and Python share one source of truth; the trained weights go back to Java
as a flat binary file read by PlaceClassifier, with batch norm folded into the convolutions.

    python train.py --data train.mtvx --val val.mtvx --arch small --aug --epochs 20 --out runs/small-aug

Writes into --out: metrics.json (per-epoch and per-class accuracy), best.pt, classifier.bin, parity.bin.
"""
import argparse, json, os, struct, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F


def read_dataset(path, limit=None):
    with open(path, 'rb') as f:
        magic = f.read(4)
        assert magic == b'MTVX', magic
        version, count, cube, materials, classes = struct.unpack('>iiiii', f.read(20))
        names = []
        for _ in range(classes):
            (n,) = struct.unpack('>H', f.read(2))
            names.append(f.read(n).decode('utf-8'))
        if limit: count = min(count, limit)
        raw = np.fromfile(f, dtype=np.uint8, count=count * (cube ** 3 + 1))
    raw = raw.reshape(count, cube ** 3 + 1)
    cubes = raw[:, :-1].reshape(count, cube, cube, cube)  # [n][x][y][z]
    labels = raw[:, -1].astype(np.int64)
    return cubes, labels, names, materials


class Net(nn.Module):
    """Stages of conv3d(k3,p1)+BN+ReLU, each followed by max pool 2; global average pool; linear."""
    def __init__(self, channels_in, classes, stages, convs_per_stage):
        super().__init__()
        layers = []
        cin = channels_in
        for cout in stages:
            for _ in range(convs_per_stage):
                layers += [nn.Conv3d(cin, cout, 3, padding=1, bias=False), nn.BatchNorm3d(cout), nn.ReLU(inplace=True)]
                cin = cout
            layers.append(nn.MaxPool3d(2))
        self.features = nn.Sequential(*layers)
        self.head = nn.Linear(cin, classes)

    def forward(self, x):
        x = self.features(x)
        x = x.mean(dim=(2, 3, 4))
        return self.head(x)


ARCHS = {
    'small': dict(stages=[16, 32, 64], convs_per_stage=1),
    'deep': dict(stages=[16, 32, 64], convs_per_stage=2),
    'wide': dict(stages=[24, 48, 96], convs_per_stage=1),
}


def one_hot(cubes_u8, materials, device):
    x = torch.from_numpy(cubes_u8).to(device).long()
    return F.one_hot(x, materials).permute(0, 4, 1, 2, 3).float()  # N, C, X, Y, Z


def augment(x):
    """Random quarter turns around the vertical axis and mirror flips: gravity keeps y, so only x and z move."""
    k = int(torch.randint(0, 4, (1,)))
    if k: x = torch.rot90(x, k, dims=(2, 4))
    if torch.rand(1) < 0.5: x = torch.flip(x, dims=(2,))
    if torch.rand(1) < 0.5: x = torch.flip(x, dims=(4,))
    return x


def clutter(x, max_fraction):
    """Random cells become random materials: torches, grass, a dropped block, a stray torch; a real world is never as clean
    as a generated one."""
    n, c = x.shape[0], x.shape[1]
    fraction = float(torch.rand(1)) * max_fraction
    mask = torch.rand(n, 1, *x.shape[2:], device=x.device) < fraction
    random_ids = torch.randint(0, c, (n, 1, *x.shape[2:]), device=x.device)
    random_hot = F.one_hot(random_ids.squeeze(1), c).permute(0, 4, 1, 2, 3).float()
    return torch.where(mask, random_hot, x)


def evaluate(model, cubes, labels, materials, device, classes, batch=512):
    model.eval()
    correct = np.zeros(classes, dtype=np.int64); total = np.zeros(classes, dtype=np.int64)
    confusion = np.zeros((classes, classes), dtype=np.int64)
    with torch.no_grad():
        for i in range(0, len(cubes), batch):
            x = one_hot(cubes[i:i + batch], materials, device)
            pred = model(x).argmax(1).cpu().numpy()
            y = labels[i:i + batch]
            for a, b in zip(y, pred):
                total[a] += 1; confusion[a, b] += 1
                if a == b: correct[a] += 1
    return correct.sum() / total.sum(), (correct / np.maximum(total, 1)).tolist(), confusion.tolist()


def fold_bn(conv, bn):
    w = conv.weight.detach().clone()
    b = torch.zeros(w.shape[0]) if conv.bias is None else conv.bias.detach().clone()
    scale = bn.weight.detach() / torch.sqrt(bn.running_var.detach() + bn.eps)
    w = w * scale.reshape(-1, 1, 1, 1, 1)
    b = (b - bn.running_mean.detach()) * scale + bn.bias.detach()
    return w.cpu().numpy().astype(np.float32), b.cpu().numpy().astype(np.float32)


def export(model, names, materials, path):
    """Flat big-endian weights: see PlaceClassifier in Java for the reader."""
    model = model.cpu().eval()
    layers = []
    mods = list(model.features)
    i = 0
    while i < len(mods):
        m = mods[i]
        if isinstance(m, nn.Conv3d):
            bn = mods[i + 1]; assert isinstance(bn, nn.BatchNorm3d)
            w, b = fold_bn(m, bn)
            layers.append(('conv', w, b)); i += 3  # conv, bn, relu
        elif isinstance(m, nn.MaxPool3d):
            layers.append(('pool',)); i += 1
        else:
            raise ValueError(m)
    layers.append(('gap',))
    layers.append(('linear', model.head.weight.detach().numpy().astype(np.float32), model.head.bias.detach().numpy().astype(np.float32)))
    with open(path, 'wb') as f:
        f.write(b'MTCL'); f.write(struct.pack('>iiii', 1, materials, 16, len(names)))
        for n in names:
            raw = n.encode('utf-8'); f.write(struct.pack('>H', len(raw))); f.write(raw)
        f.write(struct.pack('>i', len(layers)))
        for layer in layers:
            if layer[0] == 'conv':
                w, b = layer[1], layer[2]
                f.write(struct.pack('>iiiiii', 1, w.shape[1], w.shape[0], w.shape[2], 1, 1))
                f.write(w.astype('>f4').tobytes()); f.write(b.astype('>f4').tobytes())
            elif layer[0] == 'pool': f.write(struct.pack('>i', 2))
            elif layer[0] == 'gap': f.write(struct.pack('>i', 3))
            else:
                w, b = layer[1], layer[2]
                f.write(struct.pack('>iii', 4, w.shape[1], w.shape[0]))
                f.write(w.astype('>f4').tobytes()); f.write(b.astype('>f4').tobytes())
    return os.path.getsize(path)


def write_parity(model, cubes, labels, materials, path, count=12):
    model = model.cpu().eval()
    with torch.no_grad():
        x = one_hot(cubes[:count], materials, 'cpu')
        logits = model(x).numpy().astype(np.float32)
    with open(path, 'wb') as f:
        f.write(b'MTPR'); f.write(struct.pack('>iii', count, 16, logits.shape[1]))
        for i in range(count):
            f.write(cubes[i].tobytes()); f.write(struct.pack('>b', int(labels[i]))); f.write(logits[i].astype('>f4').tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--data', required=True); ap.add_argument('--val', required=True)
    ap.add_argument('--arch', default='small', choices=ARCHS.keys()); ap.add_argument('--aug', action='store_true')
    ap.add_argument('--epochs', type=int, default=20); ap.add_argument('--batch', type=int, default=128)
    ap.add_argument('--limit', type=int, default=None, help='use only the first N training samples')
    ap.add_argument('--noise', type=float, default=0.0, help='clutter augmentation: up to this fraction of cells randomised')
    ap.add_argument('--lr', type=float, default=2e-3); ap.add_argument('--out', required=True); ap.add_argument('--seed', type=int, default=1)
    args = ap.parse_args()
    torch.manual_seed(args.seed); np.random.seed(args.seed)
    os.makedirs(args.out, exist_ok=True)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    cubes, labels, names, materials = read_dataset(args.data, args.limit)
    vcubes, vlabels, _, _ = read_dataset(args.val)
    classes = len(names)
    model = Net(materials, classes, **ARCHS[args.arch]).to(device)
    params = sum(p.numel() for p in model.parameters())
    print(f'{args.arch} aug={args.aug} noise={args.noise} train={len(cubes)} val={len(vcubes)} params={params} device={device}', flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    steps = args.epochs * ((len(cubes) + args.batch - 1) // args.batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=args.lr, total_steps=steps)
    history = []; best = (0, None); started = time.time()
    for epoch in range(args.epochs):
        model.train(); order = np.random.permutation(len(cubes)); loss_sum = 0; n = 0
        for i in range(0, len(cubes), args.batch):
            idx = np.sort(order[i:i + args.batch])
            x = one_hot(cubes[idx], materials, device)
            if args.aug: x = augment(x)
            if args.noise > 0: x = clutter(x, args.noise)
            y = torch.from_numpy(labels[idx]).to(device)
            loss = F.cross_entropy(model(x), y)
            opt.zero_grad(set_to_none=True); loss.backward(); opt.step(); sched.step()
            loss_sum += loss.item() * len(idx); n += len(idx)
        acc, per_class, confusion = evaluate(model, vcubes, vlabels, materials, device, classes)
        history.append(dict(epoch=epoch + 1, loss=loss_sum / n, val_acc=acc, per_class=per_class, seconds=time.time() - started))
        print(f'epoch {epoch + 1}: loss {loss_sum / n:.4f} val {acc:.4f} ({time.time() - started:.0f}s)', flush=True)
        if acc >= best[0]:
            best = (acc, {k: v.detach().cpu().clone() for k, v in model.state_dict().items()})
            torch.save(best[1], os.path.join(args.out, 'best.pt'))
    model.load_state_dict(best[1])
    acc, per_class, confusion = evaluate(model, vcubes, vlabels, materials, device, classes)
    size = export(model, names, materials, os.path.join(args.out, 'classifier.bin'))
    write_parity(model, vcubes, vlabels, materials, os.path.join(args.out, 'parity.bin'))
    json.dump(dict(arch=args.arch, aug=args.aug, noise=args.noise, train=len(cubes), params=params, epochs=args.epochs, seconds=time.time() - started,
                   best_val_acc=acc, per_class=dict(zip(names, per_class)), confusion=confusion, names=names, weights_bytes=size, history=history),
              open(os.path.join(args.out, 'metrics.json'), 'w'), indent=1)
    print(f'best val {acc:.4f}; weights {size} bytes; {time.time() - started:.0f}s', flush=True)
    for name, pc in zip(names, per_class): print(f'  {name:8s} {pc:.3f}')


if __name__ == '__main__':
    main()
