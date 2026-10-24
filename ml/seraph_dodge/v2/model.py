"""Сеть агента: память по тикам (маленький трансформер) + внимание к снарядам + головы действия, ценности и предсказаний.

Все операции простые (Linear, LayerNorm, ReLU, softmax-внимание), чтобы повторить их на чистой Java в моде.

tokens [B, T, F]  -> эмбеддинг + позиция -> 2 слоя pre-norm трансформера (4 головы) -> последний тик h
items  [B, P, 11] -> MLP -> внимание с запросом из h (плюс «пустой» снаряд, если опасности нет) -> p
[h, p] -> MLP 128 -> mu (действие), value, aux (предсказатели: выстрел/рывок соперника скоро, смещение соперника)
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F


class Block(nn.Module):
    def __init__(self, d, heads):
        super().__init__()
        self.h = heads
        self.ln1 = nn.LayerNorm(d); self.qkv = nn.Linear(d, 3 * d); self.proj = nn.Linear(d, d)
        self.ln2 = nn.LayerNorm(d); self.ff1 = nn.Linear(d, 2 * d); self.ff2 = nn.Linear(2 * d, d)

    def forward(self, x):
        B, T, D = x.shape
        q, k, v = self.qkv(self.ln1(x)).split(D, -1)
        q = q.view(B, T, self.h, D // self.h).transpose(1, 2)
        k = k.view(B, T, self.h, D // self.h).transpose(1, 2)
        v = v.view(B, T, self.h, D // self.h).transpose(1, 2)
        att = (q @ k.transpose(-1, -2)) / math.sqrt(D // self.h)
        y = (att.softmax(-1) @ v).transpose(1, 2).reshape(B, T, D)
        x = x + self.proj(y)
        return x + self.ff2(F.relu(self.ff1(self.ln2(x))))


class AgentNet(nn.Module):
    def __init__(self, tok_dim, item_dim, act_dim, aux_dim, T, d=64, heads=4, layers=2, hidden=128):
        super().__init__()
        self.d = d
        self.emb = nn.Linear(tok_dim, d)
        self.pos = nn.Parameter(torch.zeros(T, d))
        self.blocks = nn.ModuleList([Block(d, heads) for _ in range(layers)])
        self.lnf = nn.LayerNorm(d)
        self.it1 = nn.Linear(item_dim, d); self.it2 = nn.Linear(d, d)
        self.null = nn.Parameter(torch.zeros(1, 1, d))
        self.wq = nn.Linear(d, d); self.wk = nn.Linear(d, d); self.wv = nn.Linear(d, d)
        self.fc1 = nn.Linear(2 * d, hidden); self.fc2 = nn.Linear(hidden, hidden)
        self.mu = nn.Linear(hidden, act_dim)
        self.value = nn.Linear(hidden, 1)
        self.aux = nn.Linear(hidden, aux_dim)
        self.log_std = nn.Parameter(torch.full((act_dim,), -0.5))
        nn.init.normal_(self.pos, std=0.02)
        nn.init.zeros_(self.mu.bias); self.mu.weight.data.mul_(0.01)

    def features(self, tokens, items):
        x = self.emb(tokens) + self.pos
        for b in self.blocks:
            x = b(x)
        h = self.lnf(x[:, -1])
        e = self.it2(F.relu(self.it1(items)))                    # [B, P, d]
        e = torch.cat([self.null.expand(e.shape[0], 1, -1), e], 1)
        mask = torch.cat([torch.ones_like(items[:, :1, -1]), items[..., -1]], 1) > 0.5
        q = self.wq(h).unsqueeze(1)                              # [B, 1, d]
        sc = (q * self.wk(e)).sum(-1) / math.sqrt(self.d)
        sc = sc.masked_fill(~mask, -1e9)
        p = (sc.softmax(-1).unsqueeze(-1) * self.wv(e)).sum(1)
        z = F.relu(self.fc1(torch.cat([h, p], -1)))
        return F.relu(self.fc2(z))

    def forward(self, tokens, items):
        z = self.features(tokens, items)
        return self.mu(z), self.value(z).squeeze(-1), self.aux(z)

    def act(self, tokens, items, deterministic=False):
        mu, v, aux = self(tokens, items)
        if deterministic:
            return mu, None, v
        dist = torch.distributions.Normal(mu, self.log_std.exp())
        a = dist.sample()
        return a, dist.log_prob(a).sum(-1), v
