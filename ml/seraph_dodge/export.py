"""Выгрузка сети для мода: ONNX (для проверки) и простой двоичный файл весов, который Java читает без библиотек.

python export.py runs/v1/seraph_dodge.zip out/seraph_dodge
Формат .bin (little-endian): int32 магия 0x53455241 ('SERA'), int32 число слоёв L,
затем для каждого слоя int32 in, int32 out, float32[out*in] веса (по строкам), float32[out] сдвиги.
Скрытые слои tanh, выход линейный, затем clamp [-1, 1].
"""
import struct
import sys

import numpy as np
import torch
from stable_baselines3 import PPO

from sim import SeraphArena


def main(src, dst):
    model = PPO.load(src, device="cpu")
    pol = model.policy
    layers = [m for m in pol.mlp_extractor.policy_net if isinstance(m, torch.nn.Linear)] + [pol.action_net]
    with open(dst + ".bin", "wb") as f:
        f.write(struct.pack("<ii", 0x53455241, len(layers)))
        for lin in layers:
            w = lin.weight.detach().numpy().astype("<f4"); b = lin.bias.detach().numpy().astype("<f4")
            f.write(struct.pack("<ii", w.shape[1], w.shape[0])); f.write(w.tobytes()); f.write(b.tobytes())

    class Net(torch.nn.Module):
        def __init__(self):
            super().__init__(); self.layers = torch.nn.ModuleList(layers)
        def forward(self, x):
            for lin in self.layers[:-1]:
                x = torch.tanh(lin(x))
            return torch.clamp(self.layers[-1](x), -1, 1)
    net = Net().eval()
    torch.onnx.export(net, torch.zeros(1, SeraphArena.obs_dim), dst + ".onnx", input_names=["obs"], output_names=["action"],
                      dynamic_axes={"obs": {0: "n"}, "action": {0: "n"}}, dynamo=False)
    # проверка: ручной проход по весам совпадает с torch
    x = np.random.default_rng(0).normal(size=(4, SeraphArena.obs_dim)).astype(np.float32)
    h = x
    for i, lin in enumerate(layers):
        h = h @ lin.weight.detach().numpy().T + lin.bias.detach().numpy()
        h = np.tanh(h) if i < len(layers) - 1 else np.clip(h, -1, 1)
    ref = net(torch.as_tensor(x)).detach().numpy()
    print("max diff", float(np.abs(h - ref).max()), "->", dst + ".bin", dst + ".onnx")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
