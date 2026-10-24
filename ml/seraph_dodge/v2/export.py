"""Выгрузка сети Серафима v2 для мода: именованные тензоры, которые читает boss/ai/SeraphBrain (чистая Java).

python export.py runs/sp1/duel.pt out/seraph_brain
Формат .bin (little-endian): int32 магия 0x32465253 ('SRF2'), int32 число тензоров; для каждого: int32 длина имени,
имя UTF-8, int32 число осей, int32 размеры, float32 значения (по строкам).
Рядом пишется seraph_brain_check.txt: несколько наблюдений из тренажёра и ответы сети (для SeraphBrainTest).
"""
import struct
import sys

import numpy as np
import torch

from duel import Duel, S_ACT
from selfplay import make


def main(src, dst):
    ck = torch.load(src, map_location="cpu")
    net = make("s"); net.load_state_dict(ck["s"]); net.eval()
    sd = net.state_dict()
    with open(dst + ".bin", "wb") as f:
        f.write(struct.pack("<ii", 0x32465253, len(sd)))
        for name, t in sd.items():
            a = t.detach().numpy().astype("<f4")
            b = name.encode("utf-8")
            f.write(struct.pack("<i", len(b))); f.write(b)
            f.write(struct.pack("<i", a.ndim)); f.write(struct.pack(f"<{a.ndim}i", *a.shape)); f.write(a.tobytes())
    env = Duel(4, seed=3, scripted_frac=1.0)
    for _ in range(45):
        env.step(np.zeros((4, S_ACT)), np.zeros((4, 5)))
    st, si, _, _ = env.obs()
    with torch.no_grad():
        mu, v, aux = net(torch.as_tensor(st), torch.as_tensor(si))
    with open(dst + "_check.txt", "w") as f:
        for i in range(4):
            f.write(" ".join(f"{x:.9g}" for x in st[i].ravel()) + "\n")
            f.write(" ".join(f"{x:.9g}" for x in si[i].ravel()) + "\n")
            f.write(" ".join(f"{x:.9g}" for x in torch.cat([mu[i], aux[i]]).numpy()) + "\n")
    print("ok", dst + ".bin", sum(t.numel() for t in sd.values()), "параметров")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
