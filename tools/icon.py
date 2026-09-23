"""Build `etc/icon.png` (the main window's icon) from the dolmen drawing kept in `etc/icon-src/`.

The drawing is dark purple ink on nothing, and on a grey taskbar it vanished -- so the icon carries a light
rounded tile with a border behind the stones. The tile is a STYLE picked here, not baked into the drawing, so
the icon can be rebuilt with another one (or with none) from the same source:

    python tools/icon.py                       # moonlit tile, writes etc/icon.png
    python tools/icon.py --style parchment     # another tile (the launcher's); `none` is the bare drawing
    python tools/icon.py --preview             # every style over a dark, a grey and a light taskbar

The drawing arrived as a JPG with the transparency checkerboard baked in (`dolmen-original.jpg`, kept
untouched). `dolmen-cutout.png` is the same drawing with the checkerboard flood-filled away from the picture
border: the dark outline closes the drawing, so a fill that only walks light neutral pixels never gets inside.
Delete the cutout to have it rebuilt from the JPG.

The icon is 512 x 512. `Client.java` hands that one image to `Windeye.icon`, and the toolkit reduces it with a
Lanczos filter (`PUtils.iconsizes`) to every size its system shows: the title bar's 16 to 24 px and the
taskbar's 32 to 48 px on Windows, a Linux panel's up to 256 px, and on macOS the Dock takes the 512 px image
itself. The preview reduces the same way, so what it shows is what the taskbar shows.
"""
import argparse
from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent.parent
SOURCE_DIR = REPO / "etc" / "icon-src"
ORIGINAL = SOURCE_DIR / "dolmen-original.jpg"
CUTOUT = SOURCE_DIR / "dolmen-cutout.png"
ICON = REPO / "etc" / "icon.png"
PREVIEW = SOURCE_DIR / "preview.png"

ICON_SIDE = 512
INK = (30, 26, 46)              # the drawing's outline colour: transparent pixels take it (no light halo when
                                # scaling down) and every tile's border uses it, so the tile reads as part of
                                # the drawing
DRAWING_FRACTION = 0.80         # width of the drawing over the icon's side
CORNER_FRACTION = 0.16          # tile corner radius over the side
BORDER_FRACTION = 0.035         # tile border width over the side (about 18 px at 512, 2 px at 64)

# name -> (centre colour, edge colour); the tile is a radial gradient between the two
STYLES = {
    "parchment": ((234, 220, 186), (198, 178, 132)),
    "moonlit": ((200, 210, 226), (140, 156, 184)),
    "meadow": ((182, 200, 146), (120, 150, 92)),
    "amber": ((228, 194, 134), (186, 138, 78)),
}
DEFAULT_STYLE = "moonlit"
TASKBARS = {"dark": (32, 32, 32), "grey": (76, 76, 76), "light": (243, 243, 243)}
PREVIEW_SIZES = (32, 24, 16)
PREVIEW_SIDE = 64               # the whole icon, at the left of each preview row


def looks_like_checkerboard(red, green, blue):
    brightness = (red + green + blue) / 3
    spread = max(red, green, blue) - min(red, green, blue)
    return brightness >= 150 and spread <= 22


def cut_out(original_path):
    image = Image.open(original_path).convert("RGB")
    width, height = image.size
    pixels = image.load()
    outside = bytearray(width * height)
    queue = deque()

    def try_push(x, y):
        index = y * width + x
        if outside[index]:
            return
        if looks_like_checkerboard(*pixels[x, y]):
            outside[index] = 1
            queue.append((x, y))

    for x in range(width):
        try_push(x, 0)
        try_push(x, height - 1)
    for y in range(height):
        try_push(0, y)
        try_push(width - 1, y)
    while queue:
        x, y = queue.popleft()
        if x > 0:
            try_push(x - 1, y)
        if x < width - 1:
            try_push(x + 1, y)
        if y > 0:
            try_push(x, y - 1)
        if y < height - 1:
            try_push(x, y + 1)

    result = Image.new("RGBA", (width, height))
    result_pixels = result.load()
    for y in range(height):
        row = y * width
        for x in range(width):
            if outside[row + x]:
                result_pixels[x, y] = INK + (0,)
            else:
                result_pixels[x, y] = pixels[x, y] + (255,)
    return result.crop(result.getchannel("A").getbbox())


def load_drawing():
    if not CUTOUT.exists():
        print("cutting the checkerboard out of", ORIGINAL.name)
        cut_out(ORIGINAL).save(CUTOUT)
        print("wrote", CUTOUT)
    return Image.open(CUTOUT).convert("RGBA")


def radial_gradient(side, centre_colour, edge_colour):
    # drawn small and scaled up: smooth, and cheap without numpy
    small = 64
    gradient = Image.new("RGB", (small, small))
    gradient_pixels = gradient.load()
    half = (small - 1) / 2
    for y in range(small):
        for x in range(small):
            distance = min(1.0, (((x - half) ** 2 + (y - half) ** 2) ** 0.5) / (half * 1.25))
            gradient_pixels[x, y] = tuple(
                round(centre + (edge - centre) * distance) for centre, edge in zip(centre_colour, edge_colour))
    return gradient.resize((side, side), Image.BICUBIC)


def compose(drawing, style):
    # the canvas is the drawing's width over DRAWING_FRACTION, so the drawing sits at that fraction of the side
    side = round(drawing.width / DRAWING_FRACTION)
    canvas = Image.new("RGBA", (side, side), INK + (0,))
    if style != "none":
        centre_colour, edge_colour = STYLES[style]
        radius = round(side * CORNER_FRACTION)
        border = round(side * BORDER_FRACTION)
        tile_mask = Image.new("L", (side, side), 0)
        ImageDraw.Draw(tile_mask).rounded_rectangle((0, 0, side - 1, side - 1), radius=radius, fill=255)
        canvas.paste(radial_gradient(side, centre_colour, edge_colour), (0, 0), tile_mask)
        # same box and radius as the mask: Pillow draws the outline inward, so the border is the tile's
        # outermost ring and no tile colour peeks past it at the corners
        ImageDraw.Draw(canvas).rounded_rectangle(
            (0, 0, side - 1, side - 1), radius=radius, outline=INK + (255,), width=border)
    offset = ((side - drawing.width) // 2, (side - drawing.height) // 2)
    canvas.alpha_composite(drawing, offset)
    return canvas.resize((ICON_SIDE, ICON_SIDE), Image.LANCZOS)


def preview(drawing):
    styles = ["none"] + list(STYLES)
    zoom = 2
    gap = 10
    strip_width = sum(size * zoom + gap for size in PREVIEW_SIZES) + gap
    row_height = PREVIEW_SIDE + 2 * gap
    sheet_width = gap + PREVIEW_SIDE + gap + len(TASKBARS) * (strip_width + gap)
    sheet = Image.new("RGB", (sheet_width, row_height * len(styles)), (255, 255, 255))
    for row, style in enumerate(styles):
        icon = compose(drawing, style)
        whole = icon.resize((PREVIEW_SIDE, PREVIEW_SIDE), Image.LANCZOS)
        y = row * row_height + gap
        sheet.paste(whole, (gap, y), whole)
        x = gap + PREVIEW_SIDE + gap
        for taskbar_colour in TASKBARS.values():
            strip = Image.new("RGB", (strip_width, row_height), taskbar_colour)
            strip_x = gap
            for size in PREVIEW_SIZES:
                scaled = icon.resize((size, size), Image.LANCZOS).resize((size * zoom, size * zoom), Image.NEAREST)
                strip.paste(scaled, (strip_x, (row_height - size * zoom) // 2), scaled)
                strip_x += size * zoom + gap
            sheet.paste(strip, (x, row * row_height))
            x += strip_width + gap
    sheet.save(PREVIEW)
    print("wrote", PREVIEW, "-- rows:", ", ".join(styles), "; strips:", ", ".join(TASKBARS),
          "; sizes", PREVIEW_SIZES, "shown", str(zoom) + "x")


def main():
    parser = argparse.ArgumentParser(description="build etc/icon.png from etc/icon-src/")
    parser.add_argument("--style", choices=["none"] + list(STYLES), default=DEFAULT_STYLE)
    parser.add_argument("--out", type=Path, default=ICON)
    parser.add_argument("--preview", action="store_true", help="write etc/icon-src/preview.png instead")
    arguments = parser.parse_args()
    drawing = load_drawing()
    if arguments.preview:
        preview(drawing)
        return
    compose(drawing, arguments.style).save(arguments.out)
    print("wrote", arguments.out, "with the", arguments.style, "style")


if __name__ == "__main__":
    main()
