# Bioscan mark

One mark for every surface: a disc swept along a 30° line. A solid half sits against thinning slices, the body and its
scan resolving from coarse to fine. Platinum on the Field Terminal background; it never takes a metric or domain hue and
never uses the CHARGE spectrum.

| File | Use |
|---|---|
| `mark.svg` | Master, platinum `#E4E9F0`, 256 grid. Header, docs, large sizes. |
| `mark-small.svg` | Small-size cut (solid half + 2 slices). Favicon, notification icon, anything under 24 px. |
| `mark-black.svg`, `mark-white.svg` | One-colour versions. |
| `app-icon.svg` / `.png` | The mark on `#070A0D` (FT.Bg), the launcher icon as a tile. |

Where it is used:

- Web: `console/public/favicon.svg` (small cut on a dark tile), `apple-touch-icon.png`, `mark.svg` in the side menu.
- Android launcher: `drawable/ic_launcher_foreground.xml` (inside the 66 dp safe zone), background `ic_launcher_background`
  = `#070A0D`, and the same drawable as the monochrome (themed icon) layer.
- Android TV: `tv/.../drawable/banner.xml` (mark + "FIELD TERMINAL" set in Inter SemiBold, as outlines).
- Notifications: `drawable/ic_stat_bioscan.xml` (small cut, white; Android tints it).

Rules: clear space of one slice width on every side; no tint, shadow, gradient or outline; minimum 16 px (use the
small cut below 24 px). The geometry was generated, not drawn by hand: a disc of radius 96 on a 256 grid, bands at
(146, 24), (179, 15), (203, 9) after a 14 unit shear, rotated by -30 degrees. Trademark clearance has not been checked.
