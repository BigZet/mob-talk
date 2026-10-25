"""Scale of the rebuilt cities: 1 block = 1 metre. Every size the generators use comes from here (spec §1-§2).

The player is 1.8 m tall, a door is 2 blocks, a storey is 3 blocks floor to floor (one slab row and two rows of room).
"""

STOREY = 3          # floor to floor: slab row + two rows of room (2.8 m in a real 1-464, ceiling 2.5)
ROOM_ROWS = 2       # rows of air in a room: the lower (skirting) and the upper (cornice) row of the walls
PLINTH = 1          # the first floor's slab sits one block above the ground: the plinth row
PARAPET = 1         # parapet above the roof slab
STAIR_RISE = 0.5    # one step: a half block (a slab), so a flight of three steps climbs half a storey
FLIGHT_STEPS = 3    # steps in a flight; two flights per storey
BALCONY_DEPTH = 1   # balcony slab sticks out one block (0.9-1.2 m in reality)
WINDOW_SILL = 0.5   # sill height above the floor (the lower window block is half wall, half glass)
MARGIN = 2          # around a house in its template: balcony row plus the asphalt apron (отмостка)

SERIES_SIZES = {    # series: (section length along the facade, depth), blocks, walls included
    "1-464": (20, 16),
}
