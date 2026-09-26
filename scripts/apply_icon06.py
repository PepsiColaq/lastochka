from PIL import Image, ImageFilter
from collections import Counter, deque
import os

SRC = r"C:\Users\pishi\.cursor\projects\d\assets\lastochka-icon-06-negative.png"
RES = r"D:\обход\android\app\src\main\res"

im = Image.open(SRC).convert("RGBA")
w, h = im.size
px = im.load()

small = im.resize((64, 64), Image.Resampling.BOX)
counts = Counter()
for r, g, b, a in small.getdata():
    if a < 128 or (r > 200 and g > 200 and b > 200):
        continue
    counts[(r // 8 * 8, g // 8 * 8, b // 8 * 8)] += 1
br, bg, bb = counts.most_common(1)[0][0]

# Flood-fill white bird from center (ignores outer white canvas + corner ticks)
def is_white(x, y):
    r, g, b, a = px[x, y]
    return a > 200 and r >= 250 and g >= 250 and b >= 250

sx, sy = w // 2, h // 2
assert is_white(sx, sy), "center not white"
q = deque([(sx, sy)])
seen = {(sx, sy)}
comp = []
while q:
    x, y = q.popleft()
    if not is_white(x, y):
        continue
    comp.append((x, y))
    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
        if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen:
            seen.add((nx, ny))
            q.append((nx, ny))

bird = Image.new("RGBA", (w, h), (0, 0, 0, 0))
bp = bird.load()
for x, y in comp:
    bp[x, y] = (255, 255, 255, 255)

bird = bird.filter(ImageFilter.MaxFilter(3))
bp = bird.load()
for y in range(h):
    for x in range(w):
        r, g, b, a = bp[x, y]
        bp[x, y] = (255, 255, 255, 255) if a > 64 else (0, 0, 0, 0)

bbox = bird.getbbox()
bird_c = bird.crop(bbox)
print("bg", (br, bg, bb), "pixels", len(comp), "bbox", bbox, bird_c.size)


def fit_on_canvas(src, size, scale):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = int(size * scale)
    ratio = min(target / src.width, target / src.height)
    nw, nh = max(1, int(src.width * ratio)), max(1, int(src.height * ratio))
    resized = src.resize((nw, nh), Image.Resampling.LANCZOS)
    rp = resized.load()
    for yy in range(nh):
        for xx in range(nw):
            r, g, b, a = rp[xx, yy]
            rp[xx, yy] = (255, 255, 255, 255) if (a > 140 and r > 200) else (0, 0, 0, 0)
    canvas.paste(resized, ((size - nw) // 2, (size - nh) // 2), resized)
    return canvas


def composite_full(size):
    bg_img = Image.new("RGBA", (size, size), (br, bg, bb, 255))
    return Image.alpha_composite(bg_img, fit_on_canvas(bird_c, size, 0.64))


for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(folder, exist_ok=True)
    full = composite_full(size)
    full.save(os.path.join(folder, "ic_launcher.webp"), "WEBP", quality=95)
    full.save(os.path.join(folder, "ic_launcher_round.webp"), "WEBP", quality=95)

for name, size in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    folder = os.path.join(RES, f"mipmap-{name}")
    fit_on_canvas(bird_c, size, 0.60).save(
        os.path.join(folder, "ic_launcher_foreground.webp"), "WEBP", quality=95
    )

for dens, px_size in [("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72), ("xxxhdpi", 96)]:
    ddir = os.path.join(RES, f"drawable-{dens}")
    os.makedirs(ddir, exist_ok=True)
    fit_on_canvas(bird_c, px_size, 0.96).save(os.path.join(ddir, "ic_notification.png"), "PNG")

os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
fit_on_canvas(bird_c, 96, 0.96).save(os.path.join(RES, "drawable", "ic_notification.png"), "PNG")

bg_hex = f"#{br:02X}{bg:02X}{bb:02X}"
with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
    f.write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">{bg_hex}</color>\n'
        "</resources>\n"
    )
print("ok", bg_hex)
