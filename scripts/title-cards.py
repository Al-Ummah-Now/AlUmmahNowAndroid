#!/usr/bin/env python3
"""Generate the title-card thumbnails the screenshot fixtures load ("fixture:thumbs/<name>.jpg").

Each fixture video gets two cards, one per language, so a frame only ever shows its own language:
  <name>.jpg     Arabic title (right-aligned, the glow behind it)
  <name>.en.jpg  English title (left-aligned, mirrored)
The fixture image loaders pick <name>.en.jpg when the composition's locale is not Arabic.

A card is a 16:9 gradient with a warm glow, the title at the top start edge and a short gold rule under it. The bottom
of the card stays empty: the duration badge sits in a bottom corner (bottom-end, so bottom-right in English and
bottom-left in Arabic). Long titles shrink, then wrap to two lines; they never reach the bottom half.

Titles and colours come from TITLES below; keep them in step with the test fakes (StudioLibrary.kt, feature-home,
feature-watch and feature-studio Fakes.kt, DesignScreenshotTest.kt).

Needs macOS: headless Google Chrome draws each card and `sips` encodes the JPEG.
Usage: scripts/title-cards.py [repo root]
"""
import html
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent)
MODULES = ["design", "feature-home", "feature-watch", "feature-studio", "studio-mobile"]
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

# Layout size in CSS px; the card is zoomed by SCALE so the JPEG is sharp on tablet-size cards.
WIDTH, HEIGHT, SCALE = 400, 225, 2
JPEG_QUALITY = 86

# name: (English title, Arabic title, top-start colour, bottom-end colour)
TITLES = {
    "fiqh": ("Fiqh of Prayer", "فقه الصلاة", "#251e18", "#6a532b"),
    "kahf": ("Surat Al-Kahf recitation", "تلاوة سورة الكهف", "#0b2546", "#235c6f"),
    "kahf1": ("Tafsir of Surah Al-Kahf, part 1", "تفسير سورة الكهف، الجزء الأول", "#102842", "#3a4f60"),
    "kahf2": ("Tafsir of Surah Al-Kahf, part 2", "تفسير سورة الكهف، الجزء الثاني", "#13233c", "#464267"),
    "khutbah": ("Friday khutbah: patience", "خطبة الجمعة: الصبر", "#0e322e", "#465a35"),
    "patience": ("Patience in hardship", "الصبر عند الشدائد", "#142d29", "#555f46"),
    "ramadan": ("Ramadan reminders", "تذكيرات رمضان", "#1f1633", "#774522"),
    "reminder": ("Ramadan reminder", "تذكير رمضان", "#2d1b29", "#805329"),
    "seerah": ("Seerah overview", "نظرة عامة على السيرة", "#191f39", "#5b3f29"),
    "tafsir": ("Introduction to Tafsir", "مقدمة في التفسير", "#0b2a3c", "#26403d"),
}

PAGE = """<!doctype html>
<html lang="{lang}" dir="{dir}"><head><meta charset="utf-8"><style>
  html, body {{ margin: 0; overflow: hidden; background: #000; }}
  .card {{
    position: absolute; left: 0; top: 0; width: {w}px; height: {h}px; zoom: {scale}; overflow: hidden;
    background:
      radial-gradient(circle at {glow_x}% 31%, rgba(214, 170, 88, 0.62) 0, rgba(214, 170, 88, 0.30) 22%, rgba(214, 170, 88, 0) 52%),
      linear-gradient({angle}deg, {c0} 0%, {c1} 100%);
  }}
  .title {{
    position: absolute; top: 14px; inset-inline: 30px;
    font-family: {font}; font-weight: 700; color: #f6e8c9; line-height: 1.28;
    text-shadow: 0 1px 2px rgba(0, 0, 0, 0.35);
    white-space: nowrap;
  }}
  .rule {{ display: block; width: 56px; height: 3px; margin-top: 12px; background: #e6a744; border-radius: 1.5px; }}
</style></head>
<body><div class="card"><div class="title"><span id="t">{title}</span><span class="rule"></span></div></div>
<script>
  // Shrink a long title to fit one line; below MIN it wraps to two lines instead.
  const box = document.querySelector('.title'), t = document.getElementById('t'), MAX = 29, MIN = 22, WRAP = 25;
  let size = MAX;
  t.style.fontSize = size + 'px';
  while (t.offsetWidth > box.clientWidth && size > MIN) {{ size -= 0.5; t.style.fontSize = size + 'px'; }}
  if (t.offsetWidth > box.clientWidth) {{ box.style.whiteSpace = 'normal'; t.style.fontSize = WRAP + 'px'; }}
</script></body></html>
"""

FONTS = {
    "ar": '"SF Arabic Rounded", "SF Arabic", "Geeza Pro", sans-serif',
    "en": '"Helvetica Neue", Arial, sans-serif',
}


def page(lang: str, title: str, c0: str, c1: str) -> str:
    arabic = lang == "ar"
    return PAGE.format(
        lang=lang,
        dir="rtl" if arabic else "ltr",
        w=WIDTH,
        h=HEIGHT,
        scale=SCALE,
        # The glow sits behind the title's start edge: right in Arabic, left in English.
        glow_x=72 if arabic else 28,
        angle=135 if arabic else 225,
        c0=c0,
        c1=c1,
        font=FONTS[lang],
        title=html.escape(title),
    )


def render(html_path: pathlib.Path, png: pathlib.Path) -> None:
    subprocess.run(
        [
            CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-color-profile=srgb",
            "--force-device-scale-factor=1", f"--window-size={WIDTH * SCALE},{HEIGHT * SCALE}",
            "--virtual-time-budget=2000", f"--screenshot={png}", html_path.as_uri(),
        ],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )


def main() -> None:
    out = ROOT / MODULES[0] / "src/test/resources/thumbs"
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        for name, (en, ar, c0, c1) in TITLES.items():
            for lang, title, suffix in (("ar", ar, ".jpg"), ("en", en, ".en.jpg")):
                src, png = work / f"{name}-{lang}.html", work / f"{name}-{lang}.png"
                src.write_text(page(lang, title, c0, c1), encoding="utf-8")
                render(src, png)
                subprocess.run(
                    ["sips", "-s", "format", "jpeg", "-s", "formatOptions", str(JPEG_QUALITY), str(png), "--out", str(out / f"{name}{suffix}")],
                    check=True, stdout=subprocess.DEVNULL,
                )
    for module in MODULES[1:]:
        dest = ROOT / module / "src/test/resources/thumbs"
        dest.mkdir(parents=True, exist_ok=True)
        for jpg in sorted(out.glob("*.jpg")):
            shutil.copyfile(jpg, dest / jpg.name)
    print(f"{len(TITLES) * 2} cards in {len(MODULES)} modules")


if __name__ == "__main__":
    main()
