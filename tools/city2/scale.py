"""Scale of the rebuilt cities. Every size the generators use comes from here (spec §1-§2).

A block is a metre in the street (doors 2 blocks, the player 1.8 m), but the houses are drawn roomy: a storey is 4 blocks
floor to floor, a slab row and three rows of room, so the ceiling is well above the player's head (Dmitriy, 2026-10-25:
"we don't bump our heads in our flats"), and a 1-464 section is 28 blocks along the facade instead of the drawing's 14 m.
"""

STOREY = 4          # floor to floor: slab row + three rows of room
ROOM_ROWS = 3       # rows of air in a room: the lower (skirting), the middle and the upper (cornice) row of the walls
PLINTH = 1          # the first floor's slab sits one block above the ground: the plinth row
PARAPET = 1         # parapet above the roof slab
STAIR_RISE = 0.5    # one step: a half block (a slab)
FLIGHT_STEPS = 3    # steps in a flight, then the landing is the fourth rise; two flights per storey
BALCONY_DEPTH = 1   # balcony slab sticks out one block
MARGIN = 2          # around a house in its template: balcony row plus the asphalt apron (отмостка); the porch sits in it too

SERIES_SIZES = {    # series: (section length along the facade, depth), blocks, walls included
    "1-464": (28, 19),
    "1-515-9": (42, 22),
    "1-335": (36, 21),
    "i-209a": (56, 22),
    "p-44": (44, 24),
    "1-447": (36, 22),
    "ii-03": (34, 22),
}
