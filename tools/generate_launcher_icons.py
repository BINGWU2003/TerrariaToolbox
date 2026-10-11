"""Export Android launcher resources from A1. Requires Pillow and NumPy."""

from argparse import ArgumentParser
from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/branding/terraria-toolbox-logo.png"
RES = ROOT / "app/src/main/res"
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def monochrome_mask(source):
    """Keep the blue silhouette and open its enclosed dark facial features."""
    sample = np.asarray(source.resize((768, 768), Image.Resampling.LANCZOS), dtype=np.int16)
    red, green, blue = sample[:, :, 0], sample[:, :, 1], sample[:, :, 2]
    body = (blue - red > 20) & (blue - green > 15)
    dark = body & (green < 105) & (red < 70)
    visited = np.zeros(dark.shape, dtype=bool)
    body_area = int(body.sum())
    for y, x in zip(*np.where(dark)):
        if visited[y, x]:
            continue
        pending = deque([(y, x)])
        visited[y, x] = True
        component = []
        touches_edge = False
        while pending:
            cy, cx = pending.popleft()
            component.append((cy, cx))
            touches_edge |= cy in (0, 767) or cx in (0, 767)
            for ny, nx in ((cy - 1, cx), (cy + 1, cx), (cy, cx - 1), (cy, cx + 1)):
                if 0 <= ny < 768 and 0 <= nx < 768 and dark[ny, nx] and not visited[ny, nx]:
                    visited[ny, nx] = True
                    pending.append((ny, nx))
        # The broad dark underside remains part of the silhouette.
        if not touches_edge and 8 < len(component) < int(body_area * 0.08):
            ys, xs = zip(*component)
            body[ys, xs] = False
    return Image.fromarray(np.uint8(body) * 255).resize((288, 288), Image.Resampling.LANCZOS)


def render_preview(source, mask, destination):
    canvas = Image.new("RGB", (864, 360), "#F4F7F1")
    draw = ImageDraw.Draw(canvas)
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    font = ImageFont.truetype(str(font_path), 19) if font_path.exists() else ImageFont.load_default()
    large = source.resize((224, 224), Image.Resampling.LANCZOS)
    circle = Image.new("L", (896, 896))
    ImageDraw.Draw(circle).ellipse((0, 0, 895, 895), fill=255)
    circle = circle.resize((224, 224), Image.Resampling.LANCZOS)
    canvas.paste(large, (32, 54))
    canvas.paste(large, (320, 54), circle)
    themed = Image.new("RGB", (224, 224), "#D4E8D3")
    themed.paste("#245B43", mask=mask.resize((224, 224), Image.Resampling.LANCZOS))
    canvas.paste(themed, (608, 54), circle)
    for x, label in ((32, "A1 · 方形图标"), (320, "圆形图标"), (608, "系统主题图标")):
        draw.text((x, 296), label, fill="#19382A", font=font)
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--preview", type=Path)
    arguments = parser.parse_args()
    source = Image.open(SOURCE).convert("RGB")
    if source.size != (1536, 1536):
        raise ValueError("Expected the selected 1536 x 1536 A1 source image")

    drawable = RES / "drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
    # Adaptive icons have a 108 dp layer; the original occupies its central
    # 72 dp viewport. Extend edge pixels into the outer animation margin.
    artwork = source.resize((288, 288), Image.Resampling.LANCZOS)
    padded = np.pad(np.asarray(artwork), ((72, 72), (72, 72), (0, 0)), mode="edge")
    Image.fromarray(padded).save(drawable / "launcher_foreground_artwork.png", optimize=True)

    mask = monochrome_mask(source)
    monochrome = Image.new("RGBA", (432, 432))
    white = Image.new("RGBA", (288, 288), "white")
    white.putalpha(mask)
    monochrome.paste(white, (72, 72))
    monochrome.save(drawable / "launcher_monochrome_artwork.png", optimize=True)

    for name, asset in (("foreground", "launcher_foreground_artwork"), ("monochrome", "launcher_monochrome_artwork")):
        (RES / "drawable" / ("ic_launcher_" + name + ".xml")).write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<bitmap xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:src="@drawable/{asset}"\n'
            '    android:gravity="fill"\n'
            '    android:antialias="true"\n'
            '    android:filter="true" />\n', encoding="utf-8")
    (RES / "drawable/ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        '    <solid android:color="#A1C0AE" />\n'
        '</shape>\n', encoding="utf-8")

    for density, size in DENSITIES.items():
        directory = RES / ("mipmap-" + density)
        directory.mkdir(parents=True, exist_ok=True)
        regular = source.resize((size, size), Image.Resampling.LANCZOS)
        regular.save(directory / "ic_launcher.webp", lossless=True)
        circle = Image.new("L", (size * 4, size * 4))
        ImageDraw.Draw(circle).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        rounded = regular.convert("RGBA")
        rounded.putalpha(circle.resize((size, size), Image.Resampling.LANCZOS))
        rounded.save(directory / "ic_launcher_round.webp", lossless=True)

    if arguments.preview:
        render_preview(source, mask, ROOT / arguments.preview)
    print("Exported A1: two adaptive bitmap layers and ten launcher WebP resources")


if __name__ == "__main__":
    main()
