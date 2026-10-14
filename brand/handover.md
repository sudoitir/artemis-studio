# Rahdar logo handover

## Rationale

Rahdar (راهدار) is the road-keeper: the one who watches every road and keeps it open. The mark is one road cut
through a block, shaped as the lowercase **r** of the name, running on past two edges, so nothing is closed in.
It replaces the Artemis crescent-and-bow, which belonged to the old name, and keeps that mark's one piece of
equity: teal on a dark surface.

Built for a calm, precise operations console used at 3 a.m.:
- One idea in a glance.
- One geometric rule throughout: road 45, walls 60, bends 45 and 90, on the 16 px pixel grid.
- A wordmark drawn on the same rule, whose r is the symbol's road.
- Industrial craft rather than decoration.

## What changed in the kit round

1. **Further from ReScript** (an r of a bar and a dot inside an evenly rounded red tile).
   - The old mark was an evenly rounded tile. It is now a **leaf silhouette**: the top-left and bottom-right
     corners are rounded at 90 (on the diagonal the mark mirrors across), and the top-right and bottom-left
     corners are **square**, where the road leaves the frame.
   - Walls and road are re-proportioned to the pixel grid (road 45, walls 60).
   - The comparison is in `tests/k1.png` (all variants next to ReScript) and `tests/preview-kit-3.png` (the
     shelf, colour and greyscale).
2. **Rejected on the way** (renders kept):
   - Corners sharing the road's centre (radius 150) left a notch at the foot of the lower block.
   - Two concentric arcs around a solid corner echoed the RSS feed icon (`tests/k2.png`).
3. **One master for every size.** All edges sit on the 16 px grid, so `favicon.svg` is crisp at 16 and 32 px
   and needs no separate small cut. The road's optical thinning (38 against 40 in the previous master) was
   dropped because it would break the pixel grid; at these proportions the difference is not visible.
4. **Colour.** `#0c8599`, from the same cyan scale as `#0b7285` and `#22b8cf`, is the one teal that clears 4:1
   on both white and `#0b1418`, so a single favicon works on light and dark. Theme lockups use the exact product
   tokens.
5. **A crisp ICO.** The exporter's ICO was padded off the pixel grid and blurred at 16 and 32 px
   (`tests/kit-ico-compare.png`). `favicon.ico` was rebuilt from the master with no padding; the exporter's
   version is kept in `../kit-iter/web-v1/`.

## Tests run

| Test | Result |
|---|---|
| `svg_audit.py` on all 17 kit SVGs | 98–99/100, no warnings |
| Preview sheet (`tests/preview-kit.html`; slices `preview-kit-0/1/3.png`) | Passes every background (white, grey, black, night, photo, pattern), greyscale, one-colour, blur, avatar crop, tab, header and card. Mirrored reads "7", rotated reads "J": harmless |
| Shelf against ReScript, Medium, Vercel, Framer, Patreon, Gatsby, Kafka, RabbitMQ, Grafana | Equally resolved; clearly unlike ReScript in greyscale |
| Pixel test of `favicon.svg` at 16/24/32 (`tests/kit-px-stack.png`) | 16 and 32 crisp; 24 has a half-pixel grey edge on the arm (no 15-unit grid can hit 24 px exactly) |
| Context render (`tests/kit-ctx-1.png`) | Favicon in a light and a dark tab; mark at 16/24/32/96; 24 px app header and 96 px README lockups on white and `#0b1418` |
| Web icon set (`tests/kit-web-v1.png`, `tests/kit-ico-entries.png`) | apple-touch 180, 192, 512 and maskable 512 (mark inside the circular safe zone) viewed; ICO holds crisp 16/32/48 entries |
| Lockups in all tones (`tests/kit-lockups.png`) | Horizontal, stacked and wordmark in light, dark, black and white |
| Minimum sizes (`tests/kit-min.png`) | Horizontal readable from 20 px (soft at 16); stacked from 48 px |
| Stacked iteration | v1 (gap 1.5 roads, cap 0.42) floated apart; v2 (gap 1 road, cap 0.38) adopted; v1 kept in `../kit-iter/stacked-v1/` |
| og-image | v1's background road ran behind the type; v2 moves it to bleed off the right and bottom edges; viewed |
| Contrast | Ratios in `guidelines.md`, computed with the WCAG formula |

## Open items

- **Trademark search and reverse-image search** before launch. ReScript's icon is the nearest known mark.
  I cannot give legal clearance.
- **Chrome's dark active tab** (`#35363a`): `#0c8599` measures 2.78:1 there, under the 3:1 guideline for
  graphics. Options:
  - accept it (a 3 px road shape still reads);
  - add a `prefers-color-scheme: dark` rule to the favicon that switches it to `#22b8cf`. That breaks pages that
    are light while the OS is dark, so I did not ship it.
- **A Persian reader's check.** Turned 180° the road comes down and turns left; it may read as ر or ل. This is
  not claimed anywhere.
- **Print:** CMYK and Pantone matches, and an embroidery or engraving proof (the masters are clean two-block and
  merged-outline paths, so they should cut well).
- **Not tested:** real devices (iOS home screen, Android launcher masks, Windows taskbar), fresh eyes outside
  this session, and very large signage.
- **The og-image tagline** is set in DejaVu Sans (live text in the PNG). Re-render it in the product's UI font
  if the brand adopts one.

## Sources

- The build scripts in the work directory regenerate everything:
  - `kept_road_k.py` (symbol geometry)
  - `wordmark3.py` (wordmark)
  - `build_kit_v2.py` (kit SVGs)
- `kit-iter/og-v2/og.html` is the og-image source.
