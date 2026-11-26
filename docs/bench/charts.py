"""The benchmark charts of the README and the wiki, from `./gradlew bench`'s results.

    python docs/bench/charts.py build/bench/results.json docs/bench

Writes <chart>-light.svg and <chart>-dark.svg for each chart, and results.md, the same numbers as tables.
"""
import json
import math
import sys
from pathlib import Path

THEMES = {
    'light': dict(surface='#fcfcfb', ink='#0b0b0b', secondary='#52514e', muted='#898781', grid='#e1e0d9',
                  axis='#c3c2b7', series=['#2a78d6', '#eb6834', '#1baf7a']),
    'dark': dict(surface='#1a1a19', ink='#f0efec', secondary='#c3c2b7', muted='#898781', grid='#2c2c2a',
                 axis='#383835', series=['#3987e5', '#d95926', '#199e70']),
}
FONT = "font-family=\"-apple-system, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif\""


def text(x, y, s, size=13, fill='#000', anchor='start', weight=400):
    return (f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" fill="{fill}" text-anchor="{anchor}" '
            f'font-weight="{weight}">{s}</text>')


def svg(width, height, theme, body, title):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}" {FONT} role="img" aria-label="{title}">\n'
            f'<title>{title}</title>\n'
            f'<rect width="{width}" height="{height}" rx="8" fill="{theme["surface"]}"/>\n'
            + '\n'.join(body) + '\n</svg>\n')


def legend(x, y, entries, theme):
    """Short line keys with a dot, in a row; the labels in ink."""
    out = []
    for color, label in entries:
        out.append(f'<line x1="{x}" y1="{y - 4}" x2="{x + 18}" y2="{y - 4}" stroke="{color}" stroke-width="2" '
                   f'stroke-linecap="round"/>')
        out.append(f'<circle cx="{x + 9}" cy="{y - 4}" r="4" fill="{color}" stroke="{theme["surface"]}" '
                   f'stroke-width="2"/>')
        out.append(text(x + 24, y, label, 13, theme['secondary']))
        x += 30 + 7.4 * len(label) + 18
    return out


def tick_chart(results, theme_name):
    """Milliseconds per Minecraft tick against bodies, one line per thread count."""
    theme = THEMES[theme_name]
    width, height = 760, 420
    left, right, top, bottom = 64, 150, 104, 56
    plot_w, plot_h = width - left - right, height - top - bottom
    rain = results['rain']
    counts = sorted({r['bodies'] for r in rain})
    threads = [t for t in (1, 2, 4) if any(r['threads'] == t for r in rain)]
    y_max = 50

    def px(n):
        lo, hi = math.log2(counts[0]), math.log2(counts[-1])
        return left + (math.log2(n) - lo) / (hi - lo) * plot_w

    def py(ms):
        return top + plot_h - min(ms, y_max) / y_max * plot_h

    body = [text(left, 30, 'Time per Minecraft tick', 17, theme['ink'], weight=600),
            text(left, 50, 'Bodies raining onto voxel terrain and piling up: 3 steps × 4 substeps a tick, mean over 15 s',
                 12.5, theme['secondary'])]
    for ms in (0, 10, 20, 30, 40):
        y = py(ms)
        body.append(f'<line x1="{left}" y1="{y:.1f}" x2="{left + plot_w}" y2="{y:.1f}" stroke="{theme["grid"]}" '
                    f'stroke-width="1"/>')
        body.append(text(left - 10, y + 4, f'{ms}', 12, theme['muted'], 'end'))
    budget = py(50)
    body.append(f'<line x1="{left}" y1="{budget:.1f}" x2="{left + plot_w}" y2="{budget:.1f}" stroke="{theme["axis"]}" '
                f'stroke-width="1"/>')
    body.append(text(left - 10, budget + 4, '50', 12, theme['muted'], 'end'))
    body.append(text(left + plot_w, budget + 16, 'the whole tick: 50 ms', 12, theme['muted'], 'end'))
    body.append(text(18, top + plot_h / 2, 'ms', 12, theme['muted'], 'middle'))
    for n in counts:
        body.append(text(px(n), top + plot_h + 22, f'{n:,}', 12, theme['muted'], 'middle'))
    body.append(text(left + plot_w / 2, height - 12, 'dynamic bodies', 12, theme['muted'], 'middle'))

    ends = []
    for i, t in enumerate(threads):
        color = theme['series'][i]
        points = sorted((r['bodies'], r['tickMs']['mean']) for r in rain if r['threads'] == t)
        path = ' '.join(f'{"M" if j == 0 else "L"}{px(n):.1f},{py(ms):.1f}' for j, (n, ms) in enumerate(points))
        body.append(f'<path d="{path}" fill="none" stroke="{color}" stroke-width="2" stroke-linejoin="round" '
                    f'stroke-linecap="round"/>')
        for n, ms in points:
            body.append(f'<circle cx="{px(n):.1f}" cy="{py(ms):.1f}" r="4" fill="{color}" '
                        f'stroke="{theme["surface"]}" stroke-width="2"><title>{n:,} bodies, {t} thread'
                        f'{"s" if t > 1 else ""}: {ms:.1f} ms</title></circle>')
        ends.append((py(points[-1][1]), t, points[-1][1], color))
    # end labels, kept apart without leaving their lines
    ends.sort()
    placed = []
    for y, t, ms, color in ends:
        if placed and y - placed[-1] < 16:
            y = placed[-1] + 16
        placed.append(y)
        label = f'{t} thread{"s" if t > 1 else ""} · {ms:.1f} ms'
        body.append(text(left + plot_w + 12, y + 4, label, 12.5, theme['secondary']))
    body += legend(left, 80, [(theme['series'][i], f'{t} thread{"s" if t > 1 else ""}')
                                  for i, t in enumerate(threads)], theme)
    return svg(width, height, theme, body, 'Time per Minecraft tick against dynamic bodies')


def terrain_chart(results, theme_name):
    """What a terrain change costs, on a log scale: dots, so nothing is a bar that does not start at zero."""
    theme = THEMES[theme_name]
    terrain = results['terrain']
    rows = [('Edit one block', terrain['blockEditUs']['p50'], 0),
            ('Replace a 16×16×16 section', terrain['sectionReplaceUs']['p50'], 0),
            (f'Rebuild that section as a triangle mesh ({terrain["meshTriangles"]:,} triangles)',
             terrain['meshRebuildUs']['p50'], 1)]
    width, height = 760, 268
    left, right, top = 24, 40, 86
    plot_w = width - left - right
    lo, hi = -1, 4  # 0.1 µs to 10 ms

    def px(us):
        return left + (math.log10(us) - lo) / (hi - lo) * plot_w

    speedup = terrain['meshRebuildUs']['p50'] / terrain['sectionReplaceUs']['p50']
    body = [text(left, 30, 'What a terrain change costs', 17, theme['ink'], weight=600),
            text(left, 50, f'Median of 400; a voxel section is replaced about {speedup:.0f}× faster than a mesh is '
                           f'rebuilt', 12.5, theme['secondary'])]
    row_h = 46
    axis_y = top + row_h * len(rows) - 8
    for e in range(lo, hi + 1):
        x = px(10 ** e)
        body.append(f'<line x1="{x:.1f}" y1="{top - 6}" x2="{x:.1f}" y2="{axis_y}" stroke="{theme["grid"]}" '
                    f'stroke-width="1"/>')
        label = {-1: '0.1 µs', 0: '1 µs', 1: '10 µs', 2: '100 µs', 3: '1 ms', 4: '10 ms'}[e]
        body.append(text(x, axis_y + 18, label, 12, theme['muted'], 'middle'))
    for i, (label, us, series) in enumerate(rows):
        y = top + i * row_h + 18
        color = theme['series'][series]
        body.append(text(left, y - 10, label, 12.5, theme['secondary']))
        body.append(f'<line x1="{px(10 ** lo):.1f}" y1="{y + 4}" x2="{px(us):.1f}" y2="{y + 4}" '
                    f'stroke="{theme["axis"]}" stroke-width="1"/>')
        body.append(f'<circle cx="{px(us):.1f}" cy="{y + 4}" r="5" fill="{color}" stroke="{theme["surface"]}" '
                    f'stroke-width="2"><title>{label}: {us:.1f} µs</title></circle>')
        value = f'{us:.1f} µs' if us < 1000 else f'{us / 1000:.2f} ms'
        body.append(text(px(us) + 12, y + 8, value, 12.5, theme['ink'], weight=600))
    body += legend(width - 300, 30, [(theme['series'][0], 'voxel terrain'), (theme['series'][1], 'triangle mesh')],
                   theme)
    return svg(width, height, theme, body, 'What a terrain change costs, voxel terrain against a triangle mesh')


def table(results):
    m = results['machine']
    lines = [f'Measured on {m["cpu"]} ({m["hardwareThreads"]} hardware threads), {m["os"]}, {m["java"]}.', '',
             '| Bodies | 1 thread | 2 threads | 4 threads | 8 threads | awake (mean) |',
             '|---:|---:|---:|---:|---:|---:|']
    rain = results['rain']
    for n in sorted({r['bodies'] for r in rain}):
        cells = []
        awake = 0
        for t in (1, 2, 4, 8):
            r = next((r for r in rain if r['bodies'] == n and r['threads'] == t), None)
            cells.append('–' if r is None else f'{r["tickMs"]["mean"]:.1f} ms (p95 {r["tickMs"]["p95"]:.1f})')
            awake = r['meanAwake'] if r else awake
        lines.append(f'| {n:,} | ' + ' | '.join(cells) + f' | {awake:,.0f} |')
    t = results['terrain']
    k = results['kinematic']
    q = results['rays']
    lines += ['', '| Terrain change | median | p95 |', '|---|---:|---:|',
              f'| Edit one block | {t["blockEditUs"]["p50"]:.1f} µs | {t["blockEditUs"]["p95"]:.1f} µs |',
              f'| Replace a 16×16×16 section | {t["sectionReplaceUs"]["p50"]:.1f} µs | '
              f'{t["sectionReplaceUs"]["p95"]:.1f} µs |',
              f'| Rebuild it as a triangle mesh ({t["meshTriangles"]:,} triangles) | {t["meshRebuildUs"]["p50"]:.0f} µs '
              f'| {t["meshRebuildUs"]["p95"]:.0f} µs |',
              '', '| Other | |', '|---|---:|',
              f'| Closest-hit ray cast, 48 m over terrain with {q["bodies"]:,} bodies | {q["usPerRay"]:.2f} µs |',
              f'| Driving {k["bodies"]:,} kinematic bodies to a target, one call each | '
              f'{k["oneCallEachUs"]["p50"]:.0f} µs ({k["oneCallEachUs"]["p50"] / k["bodies"] * 1000:.0f} ns a body) |']
    return '\n'.join(lines) + '\n'


def main():
    results = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        (out / f'tick-time-{theme}.svg').write_text(tick_chart(results, theme), encoding='utf-8')
        (out / f'terrain-edits-{theme}.svg').write_text(terrain_chart(results, theme), encoding='utf-8')
    (out / 'results.md').write_text(table(results), encoding='utf-8')
    (out / 'results.json').write_text(json.dumps(results, indent=2) + '\n', encoding='utf-8')
    print('wrote', out)


if __name__ == '__main__':
    main()
