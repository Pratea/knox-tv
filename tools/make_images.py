"""Gera as imagens do mod: poster.png (lista de mods do jogo), preview.png (Workshop)
e o icone da barra lateral. Poster e preview vem do logo (workshop/logo.png).

Desenho simples feito por codigo (TV antiga com o "play" na tela), pra nao
depender de arquivo de arte externo.
    python tools/make_images.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
BG = (24, 26, 28)
BODY = (92, 70, 52)
BODY_DARK = (60, 45, 34)
SCREEN = (16, 140, 190)
ACCENT = (230, 60, 60)


def font(size: int) -> ImageFont.ImageFont:
    for name in ("arialbd.ttf", "segoeuib.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def draw_tv(size: int, title: bool) -> Image.Image:
    s = size / 256
    img = Image.new("RGB", (size, size), BG)
    d = ImageDraw.Draw(img)
    top = 30 if title else 48
    # antenas
    d.line([(128 * s, (top + 10) * s), (88 * s, (top - 22) * s)], fill=BODY_DARK, width=round(4 * s))
    d.line([(128 * s, (top + 10) * s), (168 * s, (top - 22) * s)], fill=BODY_DARK, width=round(4 * s))
    # gabinete e tela
    d.rounded_rectangle([28 * s, top * s, 228 * s, (top + 150) * s], radius=18 * s, fill=BODY)
    d.rounded_rectangle([44 * s, (top + 16) * s, 184 * s, (top + 134) * s], radius=14 * s, fill=SCREEN)
    # botao de play
    cx, cy = 114 * s, (top + 75) * s
    d.polygon([(cx - 18 * s, cy - 24 * s), (cx - 18 * s, cy + 24 * s), (cx + 24 * s, cy)], fill=(255, 255, 255))
    # botoes do lado
    for i in range(3):
        y = (top + 30 + i * 32) * s
        d.ellipse([196 * s, y, 216 * s, y + 20 * s], fill=BODY_DARK)
    d.ellipse([200 * s, (top + 34) * s, 212 * s, (top + 46) * s], fill=ACCENT)
    if title:
        d.text((size / 2, 222 * s), "KNOX TV", font=font(round(34 * s)), fill=(240, 240, 240), anchor="mm")
    return img


def draw_icon(size: int) -> Image.Image:
    """Icone do botao da barra lateral: so a TV, fundo transparente."""
    tv = draw_tv(256, title=False)
    # fundo do desenho vira transparente (o botao fica por cima do jogo)
    tv = tv.convert("RGBA")
    px = tv.load()
    for y in range(tv.height):
        for x in range(tv.width):
            if px[x, y][:3] == BG:
                px[x, y] = (0, 0, 0, 0)
    return tv.crop((20, 18, 236, 218)).resize((size, size), Image.LANCZOS)


def main() -> None:
    icon = ROOT / "mod" / "KNOXTV" / "42" / "media" / "textures" / "KNOXTV_icon.png"
    icon.parent.mkdir(parents=True, exist_ok=True)
    draw_icon(64).save(icon)
    poster = ROOT / "mod" / "KNOXTV" / "42" / "poster.png"
    preview = ROOT / "workshop" / "preview.png"
    preview.parent.mkdir(exist_ok=True)
    # Logo oficial (arte do autor) quando existir; senao, a TV desenhada
    logo = ROOT / "workshop" / "logo.png"
    if logo.exists():
        art = Image.open(logo).convert("RGB")
        art.resize((512, 512), Image.LANCZOS).save(poster)
        art.resize((512, 512), Image.LANCZOS).save(preview)
    else:
        draw_tv(512, title=True).save(poster)
        draw_tv(256, title=True).save(preview)
    print("gerado:", poster, preview, icon)


if __name__ == "__main__":
    main()
