"""Builds the shared resources of the mod: the language files (every tools/lang/<part>.ru_ru.json and <part>.en_us.json
merged together) and the mod icon from the hogweed textures. Original work, GPL-3.0 like the mod.

Run from the repository root after tools/generate_hogweed.py: python tools/generate_assets.py  (needs Pillow). The output is
committed; rerun after edits.
"""
import json
from pathlib import Path

from PIL import Image

TOOLS = Path(__file__).resolve().parent
ASSETS = TOOLS.parent / 'src' / 'main' / 'resources' / 'assets' / 'rusflora'


def main():
    lang = {'ru_ru': {}, 'en_us': {}}
    for extra in sorted((TOOLS / 'lang').glob('*.json')):
        lang['ru_ru' if extra.name.endswith('.ru_ru.json') else 'en_us'].update(json.loads(extra.read_text(encoding='utf-8')))
    for code, names in lang.items():
        (ASSETS / 'lang' / f'{code}.json').write_text(json.dumps(names, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    # Icon: the white umbel over the hogweed leaf, scaled up.
    icon = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    icon.alpha_composite(Image.open(ASSETS / 'textures' / 'block' / 'hogweed_leaf.png').convert('RGBA'))
    icon.alpha_composite(Image.open(ASSETS / 'textures' / 'block' / 'hogweed_umbel_top.png').convert('RGBA'))
    icon.resize((128, 128), Image.NEAREST).save(ASSETS / 'icon.png')
    print('ok')


if __name__ == '__main__':
    main()
