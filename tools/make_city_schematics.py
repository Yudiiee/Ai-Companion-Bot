"""Writes the town designs that ship with AI Companion (structure-block .nbt files).

usage: python3 tools/make_city_schematics.py <output dir>

The companions put these together into a town (see CityPlan.java): a plaza with a fountain,
a temple, an amphitheatre, shops, a mall, community farms and a warehouse. The front of every
design is the south side (+z); "ground" in the side file is the layer level with the ground.
Cells below the ground layer that aren't set are left alone (no digging foundations out).
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_starter_schematics import Design  # noqa: E402

M = 'minecraft:'


class Town(Design):
    def __init__(self, sx, sy, sz, ground=0):
        super().__init__(sx, sy, sz)
        self.ground = ground

    def b(self, x, y, z, name, **props):
        self.put(x, y, z, M + name, **props)

    def box(self, x0, y0, z0, x1, y1, z1, name, **props):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.b(x, y, z, name, **props)

    def fill_air(self):
        # air only above the ground layer: there and under it, whatever isn't set stays as it is
        sx, sy, sz = self.size
        for x in range(sx):
            for y in range(self.ground + 1, sy):
                for z in range(sz):
                    if (x, y, z) not in self.cells:
                        self.put(x, y, z, 'minecraft:air')


def stairs(facing, half='bottom'):
    return dict(facing=facing, half=half, shape='straight', waterlogged='false')


def chest(facing):
    return dict(facing=facing, type='single', waterlogged='false')


LANTERN = dict(hanging='false', waterlogged='false')


def lamp_post(d, x, y, z, height=2):
    for i in range(height):
        d.b(x, y + i, z, 'oak_fence')
    d.b(x, y + height, z, 'lantern', **LANTERN)


# ----------------------------------------------------------------------------
# Plaza: a paved square with a fountain and four lamp posts (15x15)
# ----------------------------------------------------------------------------

def plaza():
    n = 15
    c = n // 2
    d = Town(n, 6, n, ground=1)
    for x in range(n):
        for z in range(n):
            cross = abs(x - c) <= 2 or abs(z - c) <= 2
            d.b(x, 1, z, 'polished_andesite' if cross else 'stone_bricks')
    # the fountain: a pool with a pillar and a lantern in the middle
    for x in range(c - 1, c + 2):
        for z in range(c - 1, c + 2):
            d.b(x, 0, z, 'stone_bricks')
            if (x, z) != (c, c):
                d.b(x, 1, z, 'water', level='0')
    for x in range(c - 2, c + 3):
        for z in range(c - 2, c + 3):
            if max(abs(x - c), abs(z - c)) == 2:
                d.b(x, 2, z, 'stone_brick_slab', type='bottom', waterlogged='false')
    d.b(c, 1, c, 'chiseled_stone_bricks')
    d.b(c, 2, c, 'chiseled_stone_bricks')
    d.b(c, 3, c, 'stone_bricks')
    d.b(c, 4, c, 'lantern', **LANTERN)
    for x, z in ((1, 1), (1, n - 2), (n - 2, 1), (n - 2, n - 2)):
        lamp_post(d, x, 2, z)
    # flowers by the lamps
    for x, z in ((2, 1), (1, 2), (n - 3, 1), (n - 2, 2), (2, n - 2), (1, n - 3), (n - 3, n - 2), (n - 2, n - 3)):
        d.b(x, 2, z, 'potted_poppy')
    return d


# ----------------------------------------------------------------------------
# Temple: a stone hall with a columned porch, pews and an altar with the offering chest (15x23)
# ----------------------------------------------------------------------------

def temple():
    w, l = 15, 23
    d = Town(w, 17, l)
    d.box(0, 0, 0, w - 1, 0, l - 1, 'stone_bricks')            # platform
    d.box(0, 1, 0, w - 1, 1, l - 3, 'stone_bricks')            # floor
    for x in range(w):
        d.b(x, 1, l - 2, 'stone_brick_stairs', **stairs('north'))  # front steps
    hall = 16  # the hall's front wall; the porch is in front of it
    # walls
    for y in range(2, 9):
        for z in range(0, hall + 1):
            for x in (0, w - 1):
                d.b(x, y, z, 'stone_bricks')
        for x in range(w):
            d.b(x, y, 0, 'stone_bricks')
            if not (6 <= x <= 8 and y <= 5):
                d.b(x, y, hall, 'stone_bricks')
    for x in (0, w - 1):
        for z in (3, 7, 11):
            for y in (4, 5):
                d.b(x, y, z, 'glass')
        for y in range(2, 9):
            d.b(x, y, hall, 'chiseled_stone_bricks' if y in (2, 8) else 'stone_bricks')
    # porch columns
    for x in (1, 4, 10, 13):
        for y in range(2, 9):
            d.b(x, y, l - 3, 'chiseled_stone_bricks' if y in (2, 8) else 'stone_bricks')
    # a lintel over the porch
    for x in range(w):
        d.b(x, 8, l - 3, 'stone_bricks')
    # gabled roof, the ridge running front to back
    top = 0
    for i in range(w // 2 + 1):
        y = 9 + i
        top = max(top, y)
        for z in range(0, l - 2):
            if i == w // 2:
                d.b(i, y, z, 'stone_brick_slab', type='bottom', waterlogged='false')
            else:
                d.b(i, y, z, 'stone_brick_stairs', **stairs('east'))
                d.b(w - 1 - i, y, z, 'stone_brick_stairs', **stairs('west'))
        # gable ends filled in
        for x in range(i + 1, w - 1 - i):
            if y < 9 + w // 2:
                d.b(x, y, 0, 'stone_bricks')
                d.b(x, y, l - 3, 'stone_bricks')
    # pews facing the altar (north)
    for z in (5, 7, 9, 11, 13):
        for x in list(range(2, 6)) + list(range(9, 13)):
            d.b(x, 2, z, 'spruce_stairs', **stairs('south'))
    # aisle
    for z in range(4, hall):
        for x in (6, 7, 8):
            d.b(x, 2, z, 'red_carpet')
    # the altar: a dais, two altar stones and the offering chest between them
    d.box(3, 2, 1, 11, 2, 3, 'stone_bricks')
    d.b(6, 3, 2, 'chiseled_stone_bricks')
    d.b(8, 3, 2, 'chiseled_stone_bricks')
    d.b(7, 3, 2, 'chest', **chest('south'))
    d.b(6, 4, 2, 'lantern', **LANTERN)
    d.b(8, 4, 2, 'lantern', **LANTERN)
    d.b(4, 3, 1, 'potted_poppy')
    d.b(10, 3, 1, 'potted_poppy')
    # torches along the walls
    for z in (4, 8, 12):
        d.b(1, 5, z, 'wall_torch', facing='east')
        d.b(w - 2, 5, z, 'wall_torch', facing='west')
    d.size = (w, top + 1, l)
    return d


# ----------------------------------------------------------------------------
# Amphitheatre: a stage and a half ring of seats rising away from it (25x21)
# ----------------------------------------------------------------------------

def amphitheatre():
    w, l = 25, 21
    cx, cz = 12, 5
    d = Town(w, 9, l)
    for x in range(w):
        for z in range(l):
            r = math.hypot(x - cx, z - cz)
            if z < 1 or r > 12.5:
                continue
            d.b(x, 0, z, 'stone_bricks' if r < 5 else 'cobblestone')
    # the stage and its back wall
    d.box(6, 1, 1, 18, 1, 4, 'oak_planks')
    d.box(4, 1, 0, 20, 6, 0, 'spruce_planks')
    for x in (4, 20):
        for y in range(1, 7):
            d.b(x, y, 0, 'spruce_log', axis='y')
    d.b(6, 2, 1, 'lantern', **LANTERN)
    d.b(18, 2, 1, 'lantern', **LANTERN)
    d.box(4, 0, 0, 20, 0, 0, 'cobblestone')
    for x in range(6, 19, 2):
        d.b(x, 2, 4, 'red_carpet')
    # seats: rings 6..12 out, each a step higher, all looking at the stage
    for x in range(w):
        for z in range(6, l):
            r = math.hypot(x - cx, z - cz)
            if r < 6 or r >= 12.5:
                continue
            tier = int(r) - 6
            for y in range(1, tier + 1):
                d.b(x, y, z, 'stone_bricks')
            d.b(x, tier + 1, z, 'stone_brick_stairs', **stairs('south'))
    return d


# ----------------------------------------------------------------------------
# Shop: a market stall with a counter, a till (the barrel) and stock chests (9x9)
# ----------------------------------------------------------------------------

def shop():
    n = 9
    d = Town(n, 7, n)
    d.box(0, 0, 0, n - 1, 0, n - 1, 'spruce_planks')
    for x, z in ((0, 0), (n - 1, 0), (0, n - 1), (n - 1, n - 1)):
        for y in range(1, 5):
            d.b(x, y, z, 'oak_log', axis='y')
    for y in range(1, 4):
        for x in range(1, n - 1):
            d.b(x, y, 0, 'spruce_planks')
        for z in range(1, n - 1):
            d.b(0, y, z, 'spruce_planks')
            d.b(n - 1, y, z, 'spruce_planks')
    for x in (0, n - 1):
        d.b(x, 2, 4, 'glass')
    for x in range(n):
        for z in range(n):
            d.b(x, 5, z, 'spruce_slab', type='bottom', waterlogged='false')
    for x in range(1, n - 1):
        d.b(x, 4, 0, 'spruce_planks')
    # the counter at the front, the till in the middle of it
    for x in range(1, n - 1):
        if x == 4:
            d.b(x, 1, 6, 'barrel', facing='up', open='false')
        else:
            d.b(x, 1, 6, 'spruce_slab', type='top', waterlogged='false')
    d.b(1, 2, 6, 'lantern', **LANTERN)
    d.b(7, 2, 6, 'lantern', **LANTERN)
    # stock along the back wall
    for x in (2, 4, 6):
        d.b(x, 1, 1, 'chest', **chest('south'))
    d.b(3, 1, 1, 'crafting_table')
    for x in (1, 7):
        d.b(x, 3, 1, 'wall_torch', facing='south')
    return d


# ----------------------------------------------------------------------------
# Mall: a big market hall with a dozen stalls (21x15)
# ----------------------------------------------------------------------------

def mall():
    w, l = 21, 15
    d = Town(w, 8, l)
    d.box(0, 0, 0, w - 1, 0, l - 1, 'stone_bricks')
    for y in range(1, 6):
        for x in range(w):
            d.b(x, y, 0, 'stone_bricks')
            if not (9 <= x <= 11 and y <= 3):
                d.b(x, y, l - 1, 'stone_bricks')
        for z in range(l):
            d.b(0, y, z, 'stone_bricks')
            d.b(w - 1, y, z, 'stone_bricks')
    for x in (2, 3, 6, 7, 13, 14, 17, 18):
        for y in (2, 3):
            d.b(x, y, l - 1, 'glass')
    for z in (3, 6, 9, 12):
        for y in (3, 4):
            d.b(0, y, z, 'glass')
            d.b(w - 1, y, z, 'glass')
    for x in range(w):
        for z in range(l):
            d.b(x, 6, z, 'spruce_planks')
    for x in range(w):
        d.b(x, 7, 0, 'spruce_slab', type='bottom', waterlogged='false')
        d.b(x, 7, l - 1, 'spruce_slab', type='bottom', waterlogged='false')
    # stalls: a chest and a bit of counter, along the back and both sides
    for x in (2, 5, 8, 12, 15, 18):
        d.b(x, 1, 1, 'chest', **chest('south'))
        d.b(x, 1, 3, 'spruce_slab', type='top', waterlogged='false')
        if x in (2, 8, 12, 18):
            d.b(x, 2, 3, 'lantern', **LANTERN)
    for z in (5, 8, 11):
        d.b(1, 1, z, 'chest', **chest('east'))
        d.b(w - 2, 1, z, 'chest', **chest('west'))
        d.b(3, 1, z, 'spruce_slab', type='top', waterlogged='false')
        d.b(w - 4, 1, z, 'spruce_slab', type='top', waterlogged='false')
    for z in range(4, l - 1):
        d.b(10, 1, z, 'red_carpet')
    for x in (4, 10, 16):
        d.b(x, 4, 1, 'wall_torch', facing='south')
    return d


# ----------------------------------------------------------------------------
# Community farm: wheat around a water channel, fenced, with a gate and a composter (13x11)
# ----------------------------------------------------------------------------

def farm():
    w, l = 13, 11
    d = Town(w, 4, l)
    for x in range(w):
        for z in range(l):
            edge = x in (0, w - 1) or z in (0, l - 1)
            if edge:
                d.b(x, 0, z, 'cobblestone')
                if (x, z) == (w // 2, l - 1):
                    d.b(x, 1, z, 'oak_fence_gate', facing='south', open='false', in_wall='false', powered='false')
                elif (x, z) == (0, 0):
                    d.b(x, 1, z, 'composter', level='0')
                else:
                    d.b(x, 1, z, 'oak_fence')
            elif z == l // 2:
                d.b(x, 0, z, 'water', level='0')
            else:
                d.b(x, 0, z, 'farmland', moisture='7')
                d.b(x, 1, z, 'wheat', age='0')
    for x, z in ((w - 1, 0), (0, l - 1), (w - 1, l - 1)):
        d.b(x, 2, z, 'lantern', **LANTERN)
    return d


# ----------------------------------------------------------------------------
# Warehouse: the town's shared storage, a stone shed full of chests (11x9)
# ----------------------------------------------------------------------------

def warehouse():
    w, l = 11, 9
    d = Town(w, 7, l)
    d.box(0, 0, 0, w - 1, 0, l - 1, 'cobblestone')
    for y in range(1, 5):
        for x in range(w):
            d.b(x, y, 0, 'cobblestone')
            if x != w // 2 or y > 2:
                d.b(x, y, l - 1, 'cobblestone')
        for z in range(l):
            d.b(0, y, z, 'cobblestone')
            d.b(w - 1, y, z, 'cobblestone')
    for x, z in ((0, 0), (w - 1, 0), (0, l - 1), (w - 1, l - 1)):
        for y in range(1, 5):
            d.b(x, y, z, 'oak_log', axis='y')
    for z in (3, 5):
        d.b(0, 3, z, 'glass')
        d.b(w - 1, 3, z, 'glass')
    d.b(w // 2, 1, l - 1, 'spruce_door', facing='north', half='lower', hinge='left', open='false', powered='false')
    d.b(w // 2, 2, l - 1, 'spruce_door', facing='north', half='upper', hinge='left', open='false', powered='false')
    for x in range(w):
        for z in range(l):
            d.b(x, 5, z, 'spruce_planks')
    for x in range(w):
        d.b(x, 6, 0, 'spruce_slab', type='bottom', waterlogged='false')
        d.b(x, 6, l - 1, 'spruce_slab', type='bottom', waterlogged='false')
    for x in (1, 3, 5, 7, 9):
        d.b(x, 1, 1, 'chest', **chest('south'))
        d.b(x, 2, 1, 'chest', **chest('south'))
    for z in (3, 5):
        d.b(1, 1, z, 'chest', **chest('east'))
        d.b(w - 2, 1, z, 'chest', **chest('west'))
    d.b(2, 3, 1, 'wall_torch', facing='south')
    d.b(8, 3, 1, 'wall_torch', facing='south')
    return d


SIDE = {
    'city_plaza': ('town plaza', 'plaza, town square, square, fountain',
                   'A paved 15x15 square with a fountain and lamp posts: the middle of the companions\' town.', 1),
    'city_temple': ('temple', 'town temple, church, shrine',
                    'A stone temple with a columned porch, pews and an altar. The chest on the altar is for offerings of food.', 0),
    'city_amphitheatre': ('amphitheatre', 'amphitheater, theatre, theater, arena, stage',
                          'A stage with half a ring of stone seats rising away from it.', 0),
    'city_shop': ('shop', 'market stall, store, stall',
                  'A market stall: stock chests along the back, a counter with the till (the barrel) at the front.', 0),
    'city_mall': ('mall', 'market hall, shopping mall, market',
                  'A big stone market hall with a dozen stalls, the town\'s shared market.', 0),
    'city_farm': ('community farm', 'town farm, communal farm',
                  'A fenced wheat field around a water channel, shared by the whole town.', 0),
    'city_warehouse': ('warehouse', 'storehouse, town storage, depot',
                       'A stone shed full of chests: the town\'s shared storage.', 0),
}

MAKERS = {
    # (the temple and the PvP arena come from tools/prepare_town_designs.py: town_temple, town_arena)
    'city_plaza': plaza, 'city_amphitheatre': amphitheatre, 'city_shop': shop,
    'city_mall': mall, 'city_farm': farm, 'city_warehouse': warehouse,
}


def main(out):
    os.makedirs(out, exist_ok=True)
    for name, make in MAKERS.items():
        d = make()
        d.write(os.path.join(out, name + '.nbt'))
        title, aliases, about, ground = SIDE[name]
        with open(os.path.join(out, name + '.txt'), 'w', encoding='utf-8') as f:
            f.write(f'name: {title}\naliases: {aliases}\nabout: {about}\nground: {ground}\nfront: south\n')
        blocks = sum(1 for (_, i) in d.cells.items() if not d.palette[i][0].endswith(':air'))
        print(f'{name}: {d.size[0]}x{d.size[1]}x{d.size[2]}, {blocks} blocks')


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/assets/ai-companion/schematics')
