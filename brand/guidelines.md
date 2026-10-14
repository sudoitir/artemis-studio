# Rahdar logo guidelines

## The mark

One road, cut through a block as a lowercase **r**, runs on past two edges: the way the keeper keeps open.
The mark is the two blocks the road leaves behind; the road itself is never drawn, so it always shows
whatever is behind the logo.

Construction (canvas 240, one pixel of a 16 px favicon = 15 units):

| Part | Value |
|---|---|
| Road width | 45 (3 px at 16 px) |
| Walls (road to left edge, road to top edge) | 60 (4 px) |
| Road bend | inner radius 45, outer radius 90, one centre |
| Rounded corners (top-left, bottom-right) | radius 90, on the diagonal the mark mirrors across |
| Square corners (top-right, bottom-left) | where the road leaves the tile |

Every edge lands on a whole pixel at 16 and 32 px, so `favicon.svg` is the single master for every size.

The wordmark is built on the same rule: stroke 25, bends with inner radius 25 and outer radius 50, square
junctions and square terminals. Its final **r** is the symbol's road. It is drawn as outlines; never retype it
in a font.

## Files

| Use | File |
|---|---|
| Favicon, browser tab, README, docs, landing page, anywhere one file must work on light and dark | `favicon.svg` |
| In-app header, coloured by the app's own theme (`color: var(--…)`) | `rahdar-symbol-currentcolor.svg` (inline it) |
| Symbol on light / on dark | `rahdar-symbol-light.svg` / `rahdar-symbol-dark.svg` |
| Horizontal lockup, light / dark backgrounds | `rahdar-horizontal-light.svg` / `rahdar-horizontal-dark.svg` |
| Stacked lockup, light / dark backgrounds | `rahdar-stacked-light.svg` / `rahdar-stacked-dark.svg` |
| One colour (print, engraving, embroidery, stamps) | `*-black.svg`, `*-white.svg` |
| Wordmark alone | `rahdar-wordmark-*.svg` |
| Web and PWA icons | `favicon.ico`, `apple-touch-icon.png`, `icon-192.png`, `icon-512.png`, `maskable-512.png`, `site.webmanifest`, `head-snippet.html` |
| Social card | `og-image.png` (1200 × 630) |

On GitHub, switch the README lockup with the viewer's theme:

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="rahdar-horizontal-dark.svg">
  <img src="rahdar-horizontal-light.svg" alt="Rahdar" height="96">
</picture>
```

## Colour

| Role | HEX | RGB | Contrast |
|---|---|---|---|
| Keeper teal: `favicon.svg`, any surface | `#0c8599` | 12 133 153 | 4.35:1 on white, 4.28:1 on `#0b1418` |
| Teal, mark on light | `#0b7285` | 11 114 133 | 5.59:1 on white |
| Bright teal, mark on dark | `#22b8cf` | 34 184 207 | 7.83:1 on `#0b1418` (2.38:1 on white: never use on light) |
| Night, ink on light and the dark background | `#0b1418` | 11 20 24 | 18.6:1 on white |
| Paper, wordmark on dark | `#f4feff` | 244 254 255 | 18.2:1 on `#0b1418` |
| Glow, accents on dark only (links, the og-image URL) | `#7ceaf5` | 124 234 245 | 13.3:1 on `#0b1418` |

All teals come from the product's existing cyan scale. Print values (CMYK, Pantone) still need a proof.

## Clear space

Keep one **wall** clear on every side: a quarter of the symbol's height (60 of 240). In a lockup, measure the
wall from the symbol.

## Minimum sizes

| Artwork | Screen | Print |
|---|---|---|
| Symbol | 16 px | 5 mm |
| Horizontal lockup | 20 px tall | 6 mm tall |
| Stacked lockup | 48 px tall | 14 mm tall |

## Lockup rules

- Horizontal: the symbol is full height; the wordmark's baseline sits on the symbol's bottom edge; cap height is
  0.7 of the symbol; the gap is one road width.
- Stacked: the symbol is centred over the wordmark; the gap is one road width; cap height is 0.38 of the symbol.
- Never rearrange, rescale or respace the parts; use the supplied files.

## Misuse

- Do not draw the road in a colour: it is empty space and shows the background.
- Do not put the mark inside another tile or rounded square; it is its own tile (app icons use the supplied set).
- Do not round the square corners or square the rounded ones; do not rotate or mirror the mark.
- Do not use `#22b8cf` on white or `#0b7285` on very dark grey surfaces below 3:1 (use `favicon.svg`).
- Do not add outlines, shadows, gradients or glows.
- Do not set "Rahdar" in a font next to the symbol; use the outlined wordmark.
- Do not stretch, condense or change the wordmark's spacing.
