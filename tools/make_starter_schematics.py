"""Writes the starter farm designs that ship with AI Companion as structure-block .nbt files.

usage: python3 tools/make_starter_schematics.py <output dir>

Each design is a vanilla structure file (the same format a structure block saves), so it can
also be loaded in game with a structure block. Layer 0 sits level with the ground; the front
is the south side (+z).
"""
import gzip
import os
import struct
import sys

DATA_VERSION = 4440  # any recent version; the game upgrades older structure files itself


def _str(s):
    b = s.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def _tag(t, name, payload):
    return bytes([t]) + _str(name) + payload


def _int(v):
    return struct.pack('>i', v)


def _compound(items):
    """items: list of (type, name, payload bytes)."""
    return b''.join(_tag(t, n, p) for t, n, p in items) + b'\x00'


def _list(elem_type, payloads):
    return bytes([elem_type]) + _int(len(payloads)) + b''.join(payloads)


def structure(size, palette, blocks):
    """size (x, y, z); palette: list of (name, {prop: value}); blocks: list of ((x, y, z), state index)."""
    pal = []
    for name, props in palette:
        items = [(8, 'Name', _str(name))]
        if props:
            items.append((10, 'Properties', _compound([(8, k, _str(v)) for k, v in sorted(props.items())])))
        pal.append(_compound(items))
    blk = []
    for (x, y, z), st in blocks:
        blk.append(_compound([(3, 'state', _int(st)), (9, 'pos', _list(3, [_int(x), _int(y), _int(z)]))]))
    root = _compound([
        (3, 'DataVersion', _int(DATA_VERSION)),
        (9, 'size', _list(3, [_int(size[0]), _int(size[1]), _int(size[2])])),
        (9, 'palette', _list(10, pal)),
        (9, 'blocks', _list(10, blk)),
        (9, 'entities', _list(10, [])),
    ])
    return gzip.compress(bytes([10]) + _str('') + root, mtime=0)


class Design:
    def __init__(self, sx, sy, sz):
        self.size = (sx, sy, sz)
        self.palette = []
        self.index = {}
        self.cells = {}

    def put(self, x, y, z, name, **props):
        key = (name, tuple(sorted(props.items())))
        if key not in self.index:
            self.index[key] = len(self.palette)
            self.palette.append((name, props))
        self.cells[(x, y, z)] = self.index[key]

    def fill_air(self):
        sx, sy, sz = self.size
        for x in range(sx):
            for y in range(sy):
                for z in range(sz):
                    if (x, y, z) not in self.cells:
                        self.put(x, y, z, 'minecraft:air')

    def write(self, path):
        self.fill_air()
        data = structure(self.size, self.palette, sorted(self.cells.items(), key=lambda c: (c[0][1], c[0][2], c[0][0])))
        with open(path, 'wb') as f:
            f.write(data)


def sugar_cane_farm():
    """9 wide, 9 long: three water channels with sand on both sides, capped at the ends."""
    d = Design(9, 2, 9)
    for x in range(9):
        for z in range(9):
            end = z in (0, 8)
            if x % 3 == 1 and not end:
                d.put(x, 0, z, 'minecraft:water', level='0')
            else:
                d.put(x, 0, z, 'minecraft:sand')
                if not end:
                    d.put(x, 1, z, 'minecraft:sugar_cane', age='0')
    return d


def cactus_farm():
    """7 by 7 of sand, a cactus on every other block with a walkway between."""
    d = Design(7, 2, 7)
    for x in range(7):
        for z in range(7):
            d.put(x, 0, z, 'minecraft:sand')
            if x % 2 == 0 and z % 2 == 0:
                d.put(x, 1, z, 'minecraft:cactus', age='0')
    return d


def bamboo_farm():
    """7 by 7 of grass, bamboo on every other block, torches on the walkways so it grows at night."""
    d = Design(7, 2, 7)
    for x in range(7):
        for z in range(7):
            d.put(x, 0, z, 'minecraft:grass_block', snowy='false')
            if x % 2 == 0 and z % 2 == 0:
                d.put(x, 1, z, 'minecraft:bamboo_sapling')
    for x, z in ((1, 1), (5, 1), (3, 3), (1, 5), (5, 5)):
        d.put(x, 1, z, 'minecraft:torch')
    return d


SIDE = {
    'sugar_cane_farm': ('sugar cane farm', 'cane farm, sugarcane farm',
                        '9x9: three water channels lined with sand and sugar cane. Cut it and it grows back.'),
    'cactus_farm': ('cactus farm', 'cacti farm',
                    '7x7 of sand with 16 cactus spaced out so they don\'t break each other.'),
    'bamboo_farm': ('bamboo farm', 'bamboo plantation',
                    '7x7 of grass with 16 bamboo shoots and torches so it grows at night.'),
}


def main(out):
    os.makedirs(out, exist_ok=True)
    for name, make in (('sugar_cane_farm', sugar_cane_farm), ('cactus_farm', cactus_farm), ('bamboo_farm', bamboo_farm)):
        make().write(os.path.join(out, name + '.nbt'))
        title, aliases, about = SIDE[name]
        with open(os.path.join(out, name + '.txt'), 'w', encoding='utf-8') as f:
            f.write(f'name: {title}\naliases: {aliases}\nabout: {about}\nground: 0\nfront: south\n')
    print('wrote starter designs to', out)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/assets/ai-companion/schematics')
