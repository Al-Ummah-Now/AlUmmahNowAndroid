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

The Admin review frames ("frames, flagged first") use the same card style: FRAMES below gives each fixture review video
(FixtureReviewService) a card per frame time, its title with the scene under it, written to
admin-mobile/src/test/resources/frames/<subject>-<second>.jpg and .en.jpg. The glow moves from frame to frame.

Needs macOS: headless Google Chrome draws each card and `sips` encodes the JPEG.
Usage: scripts/title-cards.py [repo root]           (every card)
       scripts/title-cards.py --frames [repo root]  (only the Admin review frames)
"""
import html
import pathlib
import shutil
import subprocess
import sys
import tempfile

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
ROOT = pathlib.Path(ARGS[0] if ARGS else pathlib.Path(__file__).resolve().parent.parent)
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

# subject: (English title, Arabic title, top-start colour, bottom-end colour, [(second, English scene, Arabic scene)])
FRAMES = {
    "v-nsfw": ("Evening market walk", "جولة في سوق المساء", "#1d1a2e", "#6b4a2a", [
        (0, "Market gate", "بوابة السوق"), (90, "Spice stalls", "أكشاك التوابل"), (195, "Crowded lane", "زقاق مزدحم"),
        (285, "Lantern shop", "محل الفوانيس"), (380, "Sunset prayer", "أذان المغرب"),
    ]),
    "v-risk": ("Charity drive update", "آخر أخبار حملة التبرعات", "#10283a", "#3f5a3a", [
        (0, "This month's work", "عمل هذا الشهر"), (60, "Food parcels", "السلال الغذائية"), (128, "How to donate", "طريقة التبرع"),
        (190, "Winter appeal", "حملة الشتاء"), (240, "Thank you", "شكرًا لكم"),
    ]),
    "v-conflict": ("Ramadan recipes", "وصفات رمضان", "#2a1a1c", "#7a4a24", [
        (0, "Ingredients", "المكونات"), (180, "Lentil soup", "شوربة العدس"), (444, "Giveaway", "مسابقة"),
        (600, "Stuffed dates", "تمر محشو"), (720, "Kunafa", "كنافة"),
    ]),
    "v-appeal": ("Debate night", "ليلة المناظرة", "#161c33", "#4b3a5e", [
        (0, "Opening", "الافتتاح"), (600, "First speaker", "المتحدث الأول"), (1297, "Heckling", "مقاطعة"),
        (2100, "Questions", "الأسئلة"), (3000, "Closing", "الختام"),
    ]),
    "v-minor": ("Swimming lesson", "تعلّم السباحة", "#0b2a3f", "#1f6070", [
        (0, "Warm-up", "الإحماء"), (72, "In the pool", "في المسبح"), (150, "Floating", "الطفو"),
        (240, "Breathing", "التنفس"), (330, "Cool-down", "الاسترخاء"),
    ]),
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
  .scene {{ display: block; margin-top: 14px; font-size: 20px; font-weight: 600; color: #f2dcae; }}
</style></head>
<body><div class="card"><div class="title"><span id="t">{title}</span><span class="rule"></span>{scene}</div></div>
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


def page(lang: str, title: str, c0: str, c1: str, scene: str = "", glow: int | None = None) -> str:
    arabic = lang == "ar"
    return PAGE.format(
        lang=lang,
        dir="rtl" if arabic else "ltr",
        w=WIDTH,
        h=HEIGHT,
        scale=SCALE,
        # The glow sits behind the title's start edge: right in Arabic, left in English.
        glow_x=glow if glow is not None else (72 if arabic else 28),
        angle=135 if arabic else 225,
        c0=c0,
        c1=c1,
        font=FONTS[lang],
        title=html.escape(title),
        scene=f'<span class="scene">{html.escape(scene)}</span>' if scene else "",
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
    frames()


def frames() -> None:
    out = ROOT / "admin-mobile/src/test/resources/frames"
    out.mkdir(parents=True, exist_ok=True)
    count = 0
    with tempfile.TemporaryDirectory() as tmp:
        work = pathlib.Path(tmp)
        for subject, (en, ar, c0, c1, scenes) in FRAMES.items():
            for i, (second, scene_en, scene_ar) in enumerate(scenes):
                for lang, title, scene, suffix in (("ar", ar, scene_ar, ".jpg"), ("en", en, scene_en, ".en.jpg")):
                    # The glow drifts across the frames of one video, so no two frames look the same.
                    glow = (80 - i * 15) if lang == "ar" else (20 + i * 15)
                    src, png = work / f"{subject}-{second}-{lang}.html", work / f"{subject}-{second}-{lang}.png"
                    src.write_text(page(lang, title, c0, c1, scene, glow), encoding="utf-8")
                    render(src, png)
                    subprocess.run(
                        ["sips", "-s", "format", "jpeg", "-s", "formatOptions", str(JPEG_QUALITY), str(png),
                         "--out", str(out / f"{subject}-{second}{suffix}")],
                        check=True, stdout=subprocess.DEVNULL,
                    )
                    count += 1
    print(f"{count} review frames in admin-mobile")


if __name__ == "__main__":
    if "--frames" in sys.argv:
        frames()
    else:
        main()
