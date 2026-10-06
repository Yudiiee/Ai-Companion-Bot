"""Splits the medieval village schematic into one structure file per house.

usage: python3 tools/split_houses.py <village.nbt> <output dir>
"""
import collections, sys, os
import nbtlib

DECOR = ('grass', 'fern', 'tulip', 'poppy', 'dandelion', 'orchid', 'allium', 'bluet', 'cornflower', 'lily_of',
         'lilac', 'rose_bush', 'leaf_litter', 'flower', 'peony', 'sunflower')
MARGIN = 2

def main(src, out):
    root = nbtlib.load(src)
    pal = root['palette'].v
    names = [p['Name'].v.split(':')[1] for p in pal]
    SX, SY, SZ = [t.v for t in root['size'].v]
    blocks = root['blocks'].v
    solid = set()
    for b in blocks:
        x, y, z = [t.v for t in b['pos'].v]
        n = names[b['state'].v]
        if y >= 1 and not n.endswith('air') and not any(d in n for d in DECOR):
            solid.add((x, z))
    seen, comps = set(), []
    for c in solid:
        if c in seen: continue
        st, comp = [c], []
        seen.add(c)
        while st:
            x, z = st.pop(); comp.append((x, z))
            for dx in range(-2, 3):
                for dz in range(-2, 3):
                    q = (x + dx, z + dz)
                    if q in solid and q not in seen: seen.add(q); st.append(q)
        if len(comp) > 50: comps.append(comp)
    # top row left to right, then bottom row
    comps.sort(key=lambda c: (min(p[1] for p in c) > SZ // 2, min(p[0] for p in c)))
    os.makedirs(out, exist_ok=True)
    result = []
    for i, comp in enumerate(comps, 1):
        x0 = max(0, min(p[0] for p in comp) - MARGIN); x1 = min(SX - 1, max(p[0] for p in comp) + MARGIN)
        z0 = max(0, min(p[1] for p in comp) - MARGIN); z1 = min(SZ - 1, max(p[1] for p in comp) + MARGIN)
        ys = [b['pos'].v[1].v for b in blocks if x0 <= b['pos'].v[0].v <= x1 and z0 <= b['pos'].v[2].v <= z1
              and not names[b['state'].v].endswith('air')]
        y1 = max(ys)
        new_blocks = []
        for b in blocks:
            x, y, z = [t.v for t in b['pos'].v]
            if not (x0 <= x <= x1 and z0 <= z <= z1 and y <= y1): continue
            nb = dict(b.v)
            nb['pos'] = nbtlib.ints(x - x0, y, z - z0)
            new_blocks.append(nbtlib.Tag(10, nb))
        r = dict(root.v)
        r['size'] = nbtlib.ints(x1 - x0 + 1, y1 + 1, z1 - z0 + 1)
        r['blocks'] = nbtlib.Tag(9, new_blocks, 10)
        r['entities'] = nbtlib.Tag(9, [], 10)
        path = os.path.join(out, f'medieval_house_{i}.nbt')
        nbtlib.save(nbtlib.Tag(10, r), path)
        result.append((path, (x0, z0, x1, z1, y1)))
        print(path, 'box', (x0, z0), (x1, z1), 'size', (x1 - x0 + 1, y1 + 1, z1 - z0 + 1), 'blocks', len(new_blocks))
    return result

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])


NAMES = ['medieval cottage', 'medieval townhouse', 'medieval inn', 'medieval workshop', 'medieval hall', 'medieval manor']


def front_of(path):
    """The side the main door looks out of: outside is opposite the door's facing; nearest the edge wins."""
    root = nbtlib.load(path)
    sx, sy, sz = [t.v for t in root['size'].v]
    pal = root['palette'].v
    best = None
    opp = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east'}
    for b in root['blocks'].v:
        p = pal[b['state'].v]
        n = p['Name'].v
        props = {k: v.v for k, v in p.get('Properties').v.items()} if p.get('Properties') else {}
        if not n.endswith('_door') or props.get('half') != 'lower' or b['pos'].v[1].v > 2:
            continue
        x, y, z = [t.v for t in b['pos'].v]
        out = opp[props['facing']]
        dist = {'west': x, 'east': sx - 1 - x, 'north': z, 'south': sz - 1 - z}[out]
        if best is None or dist < best[0]:
            best = (dist, out)
    return best[1] if best else 'south'


def write_sides(out, files):
    for i, path in enumerate(files, 1):
        base = os.path.splitext(path)[0]
        name = NAMES[i - 1]
        with open(base + '.txt', 'w', encoding='utf-8') as f:
            f.write(f'name: {name}\n')
            f.write(f'aliases: medieval house {i}, house {i}, {name.split()[1]}\n')
            f.write(f'about: Starter house {i} of 6 from a medieval village by ajmed. Spruce and dark oak timber, stone '
                    f'and calcite walls.\n')
            f.write('ground: 0\n')
            f.write(f'front: {front_of(path)}\n')
            f.write('starter: yes\n')
