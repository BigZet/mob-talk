# Советские заборы

Генератор: `python3 -I tools/city2/fences.py` (текстуры, модели, blockstates, лут, теги, названия). Java: `game/Fences.java`.
Самотест: `-Dmobtalk.selftest.only=fences` (раскладка `/city fences`, проверки стыков/столбов/ворот, скриншоты `mobtalk-fences-*.png`).

## Блоки

| id | что |
|---|---|
| `chainlink_fence_galv`, `chainlink_fence_green` | сетка-рабица на трубах (оцинкованная / в зелёном ПВХ), столб каждые 3 блока |
| `lattice_fence_green`, `_blue`, `_yellow`, `_red` | сварной забор с ромбами (школы, детсады, дворы), столб каждые 3 блока |
| `concrete_fence` | бетонный ПО-2: крупные клинья-«пирамидки» в гладкой рамке, монтажные петли сверху (промзоны, гаражи, котельные, стройки), столб = конец плиты каждые 4 блока |
| `concrete_fence_diamond` | бетонный с мелкими ромбиками-пирамидками, столб каждые 4 блока |
| `chainlink_gate_galv/green`, `lattice_gate_green/blue/yellow/red` | калитка высотой 2 блока; две с разными петлями = ворота, открываются вместе |

Один блок = 1 м забора. Высота = сколько блоков поставлено друг на друга (школа 2, детсад 1–2, ПО-2 2).
Свойства блока забора: `north/east/south/west` (стыки, как у ванильного забора), `part` = single/bottom/middle/top,
`post` = true/false, `waterlogged`. Блок сам пересчитывает их от соседей; столбы по мировой координате (x или z mod шаг),
плюс на концах, углах, у калиток и на ступеньках по высоте. Калитка — DoorBlock (`facing`, `half`, `hinge`, `open`),
створка висит посередине клетки, `facing` — куда распахивается; линия забора идёт поперёк facing.

## API

Java (сервер, после постройки):
```java
Fences.line(world, from, to, "lattice_fence_green", 2);           // прямой отрезок, стоит на from.y
Fences.gate(world, pos, Direction.SOUTH, "lattice_gate_green", true); // ворота: вторая створка справа, если смотреть по facing
```
Python (NBT-шаблоны tools/city2, house.Tpl):
```python
import fences
fences.line(tpl, x0, z0, x1, z1, y, "lattice_fence_green", height=2)
fences.gate(tpl, x, y, z, "south", "lattice_gate_green", double=True)
fences.finish(tpl)   # один раз в конце: стыки, part, post
```
Калитку ставить ДО или ПОСЛЕ line — line пропускает клетки с калиткой.
