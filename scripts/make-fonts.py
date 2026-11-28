#!/usr/bin/env python3
"""Rebuild the bundled Google Sans Flex font instances with rounded terminals.

Google Sans Flex is a variable font with a roundness axis (ROND, 0 to 100). The app ships static
instances so every weight renders the same on Android 7 to 16, cut at ROND 100 (fully rounded),
opsz 18, wdth 100 and slnt 0, and subset to Latin plus the punctuation the UI uses.

Usage:
    pip install fonttools
    curl -L -o GoogleSansFlex.ttf \\
      "https://raw.githubusercontent.com/google/fonts/main/ofl/googlesansflex/GoogleSansFlex%5BROND,opsz,slnt,wdth,wght%5D.ttf"
    python3 scripts/make-fonts.py GoogleSansFlex.ttf android/app/src/main/res/font

The font is licensed under the SIL Open Font License 1.1 (licenses/GoogleSansFlex-OFL.txt), which
has no Reserved Font Name, so the instances keep a "Google Sans Flex Rounded" family name.
"""
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROUNDNESS = 100  # 0 = the sharp default, 100 = the soft Google Health look

WEIGHTS = {
    "regular": (400, "Regular"),
    "medium": (500, "Medium"),
    "semibold": (600, "SemiBold"),
    "bold": (700, "Bold"),
    "extrabold": (800, "ExtraBold"),
    "black": (900, "Black"),
}

UNICODES = (
    "U+000D,U+0020-007E,U+00A0-00FF,U+0102,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,"
    "U+0300-0301,U+0303-0304,U+0308-0309,U+0323,U+2002,U+2009,U+200B,U+2013-2014,U+2018-201A,"
    "U+201C-201E,U+2022,U+2026,U+2039-203A,U+2044,U+20AC,U+2122,U+2212,U+2190-2193,U+2713,U+00B7,"
    "U+2264-2265"
)

FAMILY = "Google Sans Flex Rounded"


def build(src: str, outdir: str) -> None:
    for key, (weight, style) in WEIGHTS.items():
        font = TTFont(src)
        inst = instancer.instantiateVariableFont(
            font,
            {"wght": weight, "ROND": ROUNDNESS, "opsz": 18, "wdth": 100, "slnt": 0},
            updateFontNames=False,
        )

        opts = subset.Options()
        opts.layout_features = ["*"]  # keep tnum (tabular figures) for the animated counters
        opts.name_IDs = ["*"]
        opts.notdef_outline = True
        opts.drop_tables += ["STAT"]
        sub = subset.Subsetter(opts)
        sub.populate(unicodes=subset.parse_unicodes(UNICODES))
        sub.subset(inst)

        names = inst["name"]
        for rec in list(names.names):
            if rec.nameID in (16, 17, 25) or rec.nameID >= 256:
                names.removeNames(nameID=rec.nameID)
        ribbi = style in ("Regular", "Bold")
        ps_name = f"GoogleSansFlexRounded-{style}"
        names.setName(FAMILY if ribbi else f"{FAMILY} {style}", 1, 3, 1, 0x409)
        names.setName(style if ribbi else "Regular", 2, 3, 1, 0x409)
        names.setName(f"5.000;GOOG;{ps_name}", 3, 3, 1, 0x409)
        names.setName(f"{FAMILY} {style}", 4, 3, 1, 0x409)
        names.setName(ps_name, 6, 3, 1, 0x409)
        if not ribbi:
            names.setName(FAMILY, 16, 3, 1, 0x409)
            names.setName(style, 17, 3, 1, 0x409)
        inst["OS/2"].usWeightClass = weight

        out = f"{outdir}/google_sans_flex_{key}.ttf"
        inst.save(out)
        print(out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    build(sys.argv[1], sys.argv[2])
