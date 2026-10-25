"""Draws the mood faces over villagers' heads: 16 glyphs 9x9 of the font mobtalk:mood (glyphs U+E000..U+E00F).
Usage: python3 tools/mood_faces.py src/main/resources/assets/mobtalk/textures/font/mood_faces.png preview.png"""
from PIL import Image
import sys
P = {'o':'#2a1a10','e':'#1a1414','w':'#ffffff','m':'#4a1c14','r':'#d8283c','t':'#4ab0ff','y':'#ffd84a','z':'#ffffff','k':'#1e1e28','b':'#3a2216','p':'#ff6f9a'}
def shade(c, k):
    c = c.lstrip('#'); r,g,b = (int(c[i:i+2],16) for i in (0,2,4))
    f = lambda v: max(0,min(255,int(v*k)))
    return '#%02x%02x%02x' % (f(r),f(g),f(b))
# name, fill, rows (9x9)
FACES = [
 ('joyful', '#62c23c', [
  ".ooooooo.",
  "ohhfffffo",
  "ofefffefo",
  "oefefefeo",
  "offfffffo",
  "ofmmmmmfo",
  "ofsmrmsfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('cheerful', '#a6d64a', [
  ".ooooooo.",
  "ohhfffffo",
  "offfffffo",
  "ofefffefo",
  "offfffffo",
  "ofmfffmfo",
  "offmmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('even', '#ebc94c', [
  ".ooooooo.",
  "ohhfffffo",
  "offfffffo",
  "ofefffefo",
  "offfffffo",
  "offfffffo",
  "ofmmmmmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('down', '#ec9c3c', [
  ".ooooooo.",
  "ohhfffffo",
  "offfffffo",
  "ofefffefo",
  "offfffffo",
  "offmmmffo",
  "ofmfffmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('gloomy', '#6c90c8', [
  ".ooooooo.",
  "ohhfffffo",
  "obbfffbbo",
  "ofefffefo",
  "offfffffo",
  "offmmmffo",
  "ofmfffmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('panicked', '#d6c6ee', [
  ".ooooooo.",
  "ohhffftfo",
  "owwfffwwo",
  "oweffewfo"[:0] + "owefwefto",
  "offfffffo",
  "offmmmffo",
  "offmmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('tired', '#b9ab90', [
  ".ooooooo.",
  "ohhfffzzo",
  "offfffzfo",
  "ommfffmmo",
  "offfffffo",
  "offfffffo",
  "offfmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('grief', '#9a9aa8', [
  ".kkkkkkk.",
  "kkkkkkkkk",
  "okfffffko",
  "ofefffefo",
  "oftfffffo",
  "offfffffo",
  "offmmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('depressed', '#7e838c', [
  ".ooooooo.",
  "ohsfffffo",
  "offfffffo",
  "ommfffmmo",
  "ofefffefo",
  "offfffffo",
  "offmmmmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('dread', '#bba6e2', [
  ".ooooooo.",
  "ohbfffbfo",
  "owwfffwwo",
  "owefffewo",
  "owwfffwwo",
  "offmmmffo",
  "ofmfmfmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('embittered', '#da553c', [
  ".ooooooo.",
  "ohhfffffo",
  "obbfffbbo",
  "offbfbffo",
  "ofefffefo",
  "offfffffo",
  "ofmmmmmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('exhausted', '#aa9e86', [
  ".ooooooo.",
  "ohhffzzzo",
  "offfffzfo",
  "ommffzzzo",
  "offfffffo",
  "offmmmffo",
  "offmmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('lonely', '#6ea4cc', [
  ".ooooooo.",
  "ohhfffffo",
  "obfffffbo",
  "ofefffefo",
  "oftfffffo",
  "oftmmmffo",
  "ofmfffmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('happy', '#ffd43c', [
  ".ooooooo.",
  "ohhffffwo",
  "ofefffefo",
  "oefefefeo",
  "offfffffo",
  "omwwwwwmo",
  "ofmrrrmfo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('in_love', '#f28cba', [
  ".ooooooo.",
  "ohhfffffo",
  "orrfffrro",
  "orrfffrro",
  "ofrfffrfo",
  "offfffffo",
  "offmmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
 ('grace', '#f5ebb6', [
  ".yyyyyyy.",
  "y.......y"[:0] + "oyyyyyyyo",
  "ohhfffffo",
  "ofefffefo",
  "offfffffo",
  "ofmfffmfo",
  "offmmmffo",
  "osssssss" + "o",
  ".ooooooo."]),
]
def main(out, preview):
    img = Image.new('RGBA', (9*8, 9*2), (0,0,0,0))
    for i,(name, fill, rows) in enumerate(FACES):
        assert len(rows)==9 and all(len(r)==9 for r in rows), (name, [len(r) for r in rows])
        ox, oy = (i%8)*9, (i//8)*9
        for y,row in enumerate(rows):
            for x,c in enumerate(row):
                if c=='.': continue
                col = fill if c=='f' else shade(fill,1.25) if c=='h' else shade(fill,0.78) if c=='s' else P[c]
                img.putpixel((ox+x, oy+y), Image.new('RGBA',(1,1),col).getpixel((0,0)))
    img.save(out)
    img.resize((72*10, 18*10), Image.NEAREST).save(preview)
    print([n for n,_,_ in FACES])
main(sys.argv[1], sys.argv[2])
