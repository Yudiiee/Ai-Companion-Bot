"""Turns the temple and the PvP arena someone made into the town designs that ship with AI Companion.

usage: python3 tools/prepare_town_designs.py <temple.nbt> <pvp_arena.schem> <output dir>

- temple (structure .nbt, 32x28x21): the empty space inside its bounding box becomes air (so a
  hill or a tree inside it gets cleared), and an offering chest goes on the floor of the hall
  in front of the portal. Ground layer 0, front south (it's open both ways).
- PvP arena (Sponge .schem v2/v3, 75x41x75 with 7 layers of dirt under it): the natural
  ground it was saved with (dirt, grass, wild grass and flowers) is dropped, so it sits on
  whatever ground is there instead of rebuilding a hill of dirt, and the empty dirt layers
  under it go. Ground layer = the arena floor. Front west (the gate the path leads to).

Both are written as structure-block .nbt (town_temple.nbt, town_arena.nbt) plus a side file.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbtlib  # noqa: E402
from make_starter_schematics import structure  # noqa: E402


def state_text(c):
    name = c['Name'].v
    props = c.get('Properties')
    if props is None:
        return name, {}
    return name, {k: v.v for k, v in props.v.items()}


def read_structure(path):
    r = nbtlib.load(path)
    sx, sy, sz = (t.v for t in r['size'].v)
    pal = [state_text(p) for p in r['palette'].v]
    cells = {}
    for b in r['blocks'].v:
        x, y, z = (t.v for t in b['pos'].v)
        cells[(x, y, z)] = pal[b['state'].v]
    return (sx, sy, sz), cells


def parse_state(s):
    if '[' not in s:
        return s, {}
    name, rest = s.split('[', 1)
    props = {}
    for kv in rest.rstrip(']').split(','):
        if '=' in kv:
            k, v = kv.split('=', 1)
            props[k.strip()] = v.strip()
    return name, props


def read_sponge(path):
    r = nbtlib.load(path)
    s = r.v.get('Schematic', r)
    w, h, l = s['Width'].v, s['Height'].v, s['Length'].v
    blocks = s.get('Blocks')
    if blocks is not None:  # v3
        palette, data = blocks['Palette'].v, blocks['Data'].v
    else:  # v2
        palette, data = s['Palette'].v, s['BlockData'].v
    by_id = {t.v: parse_state(k) for k, t in palette.items()}
    vals, cur, shift = [], 0, 0
    for byte in data:
        cur |= (byte & 0x7F) << shift
        if byte & 0x80:
            shift += 7
            continue
        vals.append(cur)
        cur, shift = 0, 0
    cells = {}
    for i, v in enumerate(vals):
        x = i % w
        z = (i // w) % l
        y = i // (w * l)
        cells[(x & 0xFFFF, y, z)] = by_id[v]
    return (w, h, l), cells


def write(size, cells, path):
    palette, index, blocks = [], {}, []
    for pos in sorted(cells, key=lambda p: (p[1], p[2], p[0])):
        name, props = cells[pos]
        key = (name, tuple(sorted(props.items())))
        if key not in index:
            index[key] = len(palette)
            palette.append((name, props))
        blocks.append((pos, index[key]))
    with open(path, 'wb') as f:
        f.write(structure(size, palette, blocks))


def temple(src, out):
    (sx, sy, sz), cells = read_structure(src)
    for x in range(sx):
        for y in range(1, sy):
            for z in range(sz):
                cells.setdefault((x, y, z), ('minecraft:air', {}))
    # the offering chest: on the hall floor, just south of the portal, facing it
    cells[(14, 2, 12)] = ('minecraft:chest', {'facing': 'north', 'type': 'single', 'waterlogged': 'false'})
    write((sx, sy, sz), cells, os.path.join(out, 'town_temple.nbt'))
    with open(os.path.join(out, 'town_temple.txt'), 'w', encoding='utf-8') as f:
        f.write('name: temple\naliases: town temple, grand temple, portal temple, shrine\n'
                'about: A ruined-stone temple around a nether portal. The chest in front of the portal is for offerings of food.\n'
                'ground: 0\nfront: south\n')
    return sx, sy, sz, sum(1 for v in cells.values() if v[0] != 'minecraft:air')


NATURAL = {'minecraft:dirt', 'minecraft:grass_block', 'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:dandelion',
           'minecraft:azure_bluet', 'minecraft:cornflower', 'minecraft:oxeye_daisy', 'minecraft:poppy', 'minecraft:fern',
           'minecraft:large_fern'}


def arena(src, out, floor=7):
    (sx, sy, sz), cells = read_sponge(src)
    keep = {p: v for p, v in cells.items() if v[0] not in NATURAL}
    # under the floor: only what isn't air (the redstone and its blocks); no digging out the ground
    keep = {p: v for p, v in keep.items() if p[1] > floor or v[0] != 'minecraft:air'}
    low = min(p[1] for p, v in keep.items() if v[0] != 'minecraft:air')
    keep = {(x, y - low, z): v for (x, y, z), v in keep.items() if y >= low}
    size = (sx, sy - low, sz)
    write(size, keep, os.path.join(out, 'town_arena.nbt'))
    with open(os.path.join(out, 'town_arena.txt'), 'w', encoding='utf-8') as f:
        f.write('name: pvp arena\naliases: arena, pvp arena, colosseum, fighting arena, duel arena\n'
                'about: A big sandstone PvP arena with stands, banners and a redstone centrepiece. Duels in town happen here.\n'
                f'ground: {floor - low}\nfront: west\n')
    return size + (sum(1 for v in keep.values() if v[0] != 'minecraft:air'), floor - low)


if __name__ == '__main__':
    t, a, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    print('temple', temple(t, out))
    print('arena', arena(a, out))
