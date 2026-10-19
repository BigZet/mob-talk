"""The label hierarchy of the place classifier: a function of the place (what it is for) above a concrete class.

Every source maps to one fine class: generated scenes by their generator label, harvested cubes by structure / piece / tree
feature / biome, cubes cut from saves by the rules (rules.py) or the VLM. Fine classes carry their function and a Russian name;
the trained weights store the Russian names, so the game prints them as they are.
"""

FUNCTIONS = ['природа', 'жилище', 'хозяйство', 'дорога/транспорт', 'оборона', 'ловушка/заключение', 'подземелье/шахта', 'ванильная структура']
# The VLM may also answer these two; they never become training labels.
VLM_FUNCTIONS = FUNCTIONS + ['декор', 'неизвестно']

# key: (function, Russian name)
FINE = {
    # player-made places (rules on saves) merged with the generator's classes of the same meaning
    'house': ('жилище', 'дом'), 'basement': ('жилище', 'подвал'),
    'farm': ('хозяйство', 'ферма'), 'pen': ('хозяйство', 'загон'), 'storage': ('хозяйство', 'склад'),
    'road': ('дорога/транспорт', 'дорога'), 'bridge': ('дорога/транспорт', 'мост'), 'pier': ('дорога/транспорт', 'пристань'),
    'tower': ('оборона', 'башня'), 'wall': ('оборона', 'стена или ограда'),
    'cage': ('ловушка/заключение', 'клетка'), 'trap': ('ловушка/заключение', 'ловушка'), 'pit': ('ловушка/заключение', 'яма'),
    'chamber': ('ловушка/заключение', 'закрытая камера'), 'funnel': ('ловушка/заключение', 'воронка'), 'pillar': ('ловушка/заключение', 'столб'),
    'mine': ('подземелье/шахта', 'шахта'),
    'open': ('природа', 'открытое место'), 'pond': ('природа', 'водоём'),
    # nature
    'tree_oak': ('природа', 'дуб'), 'tree_birch': ('природа', 'берёза'), 'tree_spruce': ('природа', 'ель'), 'tree_jungle': ('природа', 'тропическое дерево'),
    'tree_acacia': ('природа', 'акация'), 'tree_dark_oak': ('природа', 'тёмный дуб'), 'tree_mangrove': ('природа', 'мангровое дерево'),
    'tree_cherry': ('природа', 'вишня'), 'tree_azalea': ('природа', 'азалия'), 'tree_fungus': ('природа', 'огромный адский гриб'),
    'tree_mushroom': ('природа', 'огромный гриб'),
    'bg_forest': ('природа', 'лес'), 'bg_plains': ('природа', 'равнина'), 'bg_desert': ('природа', 'пустыня'), 'bg_snowy': ('природа', 'снега'),
    'bg_mountain': ('природа', 'горы'), 'bg_swamp': ('природа', 'болото'), 'bg_jungle': ('природа', 'джунгли'), 'bg_shore': ('природа', 'берег'),
    'bg_water': ('природа', 'вода, река или море'), 'bg_cave': ('природа', 'пещера'), 'bg_nether': ('природа', 'Незер'),
    # vanilla structures
    'village_house': ('ванильная структура', 'деревенский дом'), 'village_church': ('ванильная структура', 'деревенская церковь'),
    'village_smithy': ('ванильная структура', 'деревенская кузница'), 'village_workshop': ('ванильная структура', 'мастерская жителя'),
    'village_farm': ('ванильная структура', 'деревенское поле'), 'village_pen': ('ванильная структура', 'деревенский загон'),
    'village_center': ('ванильная структура', 'площадь деревни'), 'village_street': ('ванильная структура', 'деревенская улица'),
    'outpost': ('ванильная структура', 'аванпост разбойников'), 'mineshaft': ('подземелье/шахта', 'заброшенная шахта'),
    'stronghold': ('подземелье/шахта', 'подземная крепость'), 'fortress': ('ванильная структура', 'адская крепость'),
    'bastion': ('ванильная структура', 'бастион'), 'desert_temple': ('ванильная структура', 'пустынный храм'),
    'jungle_temple': ('ванильная структура', 'храм в джунглях'), 'swamp_hut': ('ванильная структура', 'хижина ведьмы'), 'igloo': ('ванильная структура', 'иглу'),
    'ruined_portal': ('ванильная структура', 'разрушенный портал'), 'shipwreck': ('ванильная структура', 'затонувший корабль'),
    'ocean_ruin': ('ванильная структура', 'подводные руины'), 'monument': ('ванильная структура', 'подводный монумент'),
    'mansion': ('ванильная структура', 'лесной особняк'), 'ancient_city': ('подземелье/шахта', 'древний город'),
    'trail_ruins': ('ванильная структура', 'древние руины'), 'trial_chambers': ('подземелье/шахта', 'испытательные палаты'),
    'dungeon': ('подземелье/шахта', 'темница'),
}
FINE_KEYS = list(FINE)

SYNTHETIC = {'PIT': 'pit', 'CHAMBER': 'chamber', 'FUNNEL': 'funnel', 'CAGE': 'cage', 'HOUSE': 'house', 'FARM': 'farm', 'MINE': 'mine', 'TRAP': 'trap',
             'OPEN': 'open', 'PILLAR': 'pillar', 'POND': 'pond'}

_TREES = {'oak': 'tree_oak', 'fancy_oak': 'tree_oak', 'swamp_oak': 'tree_oak', 'oak_bees_005': 'tree_oak', 'birch': 'tree_birch',
          'super_birch_bees_0002': 'tree_birch', 'spruce': 'tree_spruce', 'pine': 'tree_spruce', 'mega_spruce': 'tree_spruce', 'mega_pine': 'tree_spruce',
          'jungle_tree': 'tree_jungle', 'mega_jungle_tree': 'tree_jungle', 'jungle_bush': 'tree_jungle', 'acacia': 'tree_acacia', 'dark_oak': 'tree_dark_oak',
          'mangrove': 'tree_mangrove', 'tall_mangrove': 'tree_mangrove', 'cherry': 'tree_cherry', 'azalea_tree': 'tree_azalea',
          'crimson_fungus_planted': 'tree_fungus', 'warped_fungus_planted': 'tree_fungus', 'huge_red_mushroom': 'tree_mushroom', 'huge_brown_mushroom': 'tree_mushroom'}

_STRUCTURES = {'pillager_outpost': 'outpost', 'mineshaft': 'mineshaft', 'mineshaft_mesa': 'mineshaft', 'stronghold': 'stronghold', 'fortress': 'fortress',
               'bastion_remnant': 'bastion', 'desert_pyramid': 'desert_temple', 'jungle_pyramid': 'jungle_temple', 'swamp_hut': 'swamp_hut', 'igloo': 'igloo',
               'monument': 'monument', 'mansion': 'mansion', 'ancient_city': 'ancient_city', 'trail_ruins': 'trail_ruins', 'trial_chambers': 'trial_chambers',
               'ocean_ruin_cold': 'ocean_ruin', 'ocean_ruin_warm': 'ocean_ruin', 'shipwreck': 'shipwreck', 'shipwreck_beached': 'shipwreck'}

_VILLAGE = [('fountain', 'village_center'), ('meeting_point', 'village_center'), ('town_center', 'village_center'), ('streets', 'village_street'),
            ('terminators', 'village_street'), ('temple', 'village_church'), ('weaponsmith', 'village_smithy'), ('armorer', 'village_smithy'),
            ('tool_smith', 'village_smithy'), ('library', 'village_workshop'), ('cartographer', 'village_workshop'), ('butcher', 'village_workshop'),
            ('fisher', 'village_workshop'), ('fletcher', 'village_workshop'), ('mason', 'village_workshop'), ('shepherd', 'village_workshop'),
            ('tannery', 'village_workshop'), ('animal_pen', 'village_pen'), ('stable', 'village_pen'), ('farm', 'village_farm'), ('lamp', 'village_street'),
            ('accessory', 'village_street'), ('decor', 'village_street'), ('house', 'village_house'), ('hut', 'village_house'), ('cottage', 'village_house')]

_BIOMES = [('mangrove_swamp', 'bg_swamp'), ('swamp', 'bg_swamp'), ('jungle', 'bg_jungle'), ('bamboo', 'bg_jungle'), ('beach', 'bg_shore'),
           ('stony_shore', 'bg_shore'), ('peaks', 'bg_mountain'), ('windswept', 'bg_mountain'), ('slopes', 'bg_mountain'), ('grove', 'bg_mountain'),
           ('snowy', 'bg_snowy'), ('frozen', 'bg_snowy'), ('ice', 'bg_snowy'), ('desert', 'bg_desert'), ('badlands', 'bg_desert'), ('forest', 'bg_forest'),
           ('taiga', 'bg_forest'), ('cherry', 'bg_forest'), ('plains', 'bg_plains'), ('meadow', 'bg_plains'), ('savanna', 'bg_plains'),
           ('mushroom', 'bg_plains'), ('river', 'bg_water'), ('ocean', 'bg_water')]


def bare(s):
    return s.split(':', 1)[1] if s and ':' in s else (s or '')


def village_piece(template):
    path = bare(template).replace('/zombie/', '/')
    for word, key in _VILLAGE:
        if word in path: return key
    return None


def biome_group(biome):
    b = bare(biome)
    for word, key in _BIOMES:
        if word in b: return key
    return None


def fine_of(meta):
    """The fine class key of one sample's metadata, or None when it carries no usable label."""
    if 'fine' in meta: return meta['fine']
    kind = meta.get('kind')
    if kind == 'structure':
        s = bare(meta.get('structure', ''))
        if s.startswith('village_'): return village_piece(meta.get('template') or '')
        if s.startswith('ruined_portal'): return 'ruined_portal'
        return _STRUCTURES.get(s)
    if kind == 'tree':
        return _TREES.get(bare(meta.get('feature', '')))
    if kind == 'background':
        relief = meta.get('relief')
        if relief in ('cave', 'nether', 'water'): return {'cave': 'bg_cave', 'nether': 'bg_nether', 'water': 'bg_water'}[relief]
        return biome_group(meta.get('biome', ''))
    return None


def function_of(fine):
    return FINE[fine][0]
