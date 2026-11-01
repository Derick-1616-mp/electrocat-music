import os
from PIL import Image

RES = "android/app/src/main/res"
icon = Image.open("ecm-icon-512.png").convert("RGBA")

legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
adaptive = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

for d in legacy:
    folder = os.path.join(RES, "mipmap-" + d)
    os.makedirs(folder, exist_ok=True)
    small = icon.resize((legacy[d], legacy[d]), Image.LANCZOS)
    small.save(os.path.join(folder, "ic_launcher.png"))
    small.save(os.path.join(folder, "ic_launcher_round.png"))
    size = adaptive[d]
    canvas = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    inner = int(size * 0.60)
    logo = icon.resize((inner, inner), Image.LANCZOS)
    canvas.paste(logo, ((size - inner) // 2, (size - inner) // 2), logo)
    canvas.save(os.path.join(folder, "ecm_fg.png"))

os.makedirs(os.path.join(RES, "values"), exist_ok=True)
with open(os.path.join(RES, "values", "ecm_colors.xml"), "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ecm_icon_bg">#FFFFFF</color>\n</resources>\n')

adaptive_xml = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/ecm_icon_bg"/>\n'
    '    <foreground android:drawable="@mipmap/ecm_fg"/>\n'
    '</adaptive-icon>\n'
)
folder = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(folder, exist_ok=True)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(folder, name), "w") as f:
        f.write(adaptive_xml)

print("icons written")
