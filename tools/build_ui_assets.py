"""Build the local UI POC artwork from a design reference screenshot.

The design draft is a private reference image. The crops below are only used
as placeholder artwork for the local prototype; replace them with licensed
stills and posters before shipping.

Usage:
    py -3 tools/build_ui_assets.py path/to/ui-reference.png
"""

from pathlib import Path
import sys

from PIL import Image, ImageEnhance, ImageFilter, ImageOps


OUT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res" / "drawable-nodpi"


def fit(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    return ImageOps.fit(image, size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.45))


def detail_frame(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Build a clean, wide detail backdrop without baked-in card captions."""
    canvas = fit(image, size)
    canvas = canvas.filter(ImageFilter.GaussianBlur(26))
    canvas = ImageEnhance.Brightness(canvas).enhance(0.45)
    foreground = image.resize(
        (size[0], round(image.height * size[0] / image.width)),
        Image.Resampling.LANCZOS,
    )
    canvas.paste(foreground, (0, (size[1] - foreground.height) // 2))
    return canvas


def save(image: Image.Image, name: str) -> None:
    image.save(OUT / f"{name}.webp", "WEBP", quality=92, method=6)


def main() -> None:
    if len(sys.argv) > 1:
        source_path = Path(sys.argv[1])
    else:
        source_path = Path(__file__).resolve().parents[1] / "docs" / "reference" / "ui-reference.png"
    if not source_path.is_file():
        raise SystemExit(f"Reference image not found: {source_path}")

    OUT.mkdir(parents=True, exist_ok=True)
    source = Image.open(source_path).convert("RGB")

    hero = source.crop((470, 70, 1536, 478))
    save(fit(hero, (2048, 750)), "hero_till_end_of_moon")
    save(fit(hero, (640, 360)), "banner")
    save(fit(hero, (1600, 700)), "detail_changyue")

    continue_cards = {
        "continue_changyue": (70, 528, 347, 671),
        "continue_sandadui": (365, 528, 642, 671),
        "continue_wind": (661, 528, 938, 671),
        "continue_one_piece": (957, 528, 1234, 671),
        "continue_rose": (1253, 528, 1530, 671),
    }
    for name, box in continue_cards.items():
        save(fit(source.crop(box), (640, 360)), name)
        if name != "continue_changyue":
            detail_name = "detail_" + name.removeprefix("continue_")
            save(detail_frame(source.crop((box[0], box[1], box[2], box[1] + 92)), (1600, 700)),
                 detail_name)

    poster_cards = {
        "poster_wandering_earth": (70, 790, 298, 913),
        "poster_joy_of_life": (316, 790, 544, 913),
        "poster_wind": (562, 790, 790, 913),
        "poster_kuangbiao": (808, 790, 1036, 913),
        "poster_castle_in_sky": (1054, 790, 1282, 913),
        "poster_douluo": (1300, 790, 1528, 913),
    }
    for name, box in poster_cards.items():
        save(fit(source.crop(box), (640, 360)), name)
        detail_name = "detail_" + name.removeprefix("poster_")
        save(detail_frame(source.crop((box[0], box[1], box[2], box[1] + 80)), (1600, 700)),
             detail_name)


if __name__ == "__main__":
    main()
