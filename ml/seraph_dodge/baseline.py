import numpy as np, time, sys
from sim import *
def run(dodge, weapons=None, skill=None, n=256, eps=2):
    env=SeraphArena(n, seed=1, weapons=weapons, skill=skill, dodge=dodge)
    o=env.reset_all(); assert o.shape[1]==SeraphArena.obs_dim, o.shape
    shots=hits=0
    for _ in range(EP_LEN*eps):
        o,r,d,info,_=env.step(np.zeros((n,4)))
        shots+=info["shots"].sum(); hits+=info["hits"].sum()
    return shots,hits
t=time.time()
for w,name in [([BOW],'лук'),([CROSSBOW],'арбалет'),([TRIDENT],'трезубец'),([SNOWBALL],'снежки'),([POTION],'зелье'),(None,'смесь')]:
    s,h=run(False,w)
    print(f"{name:10s} выстрелов {s:7.0f} попаданий {h:7.0f} доля {h/max(s,1):.2f}")
print("сек", time.time()-t)
