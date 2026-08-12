#!/usr/bin/env python3
"""Offline previewer for Control Panel element models.

Parses vanilla/Blockbench element-model JSONs, applies the same placement math
as ControlPanelBlockRenderer (natural scale: 1 model sixteenth = 1 grid cell,
X->grid-u, Z->grid-v, Y->outward normal) and renders orthographic PNGs so
model orientation/scale/part-transforms can be reviewed WITHOUT launching the
game.

Usage:
    python3 tools/panel_preview.py <models_dir> <textures_dir> <out_dir>

The scene list at the bottom defines what gets rendered (per-element states +
a composed panel).
"""

import json
import math
import os
import sys

from PIL import Image

# ── model loading ──────────────────────────────────────────────────────────


def load_model(models_dir, name):
    with open(os.path.join(models_dir, name + ".json")) as f:
        return json.load(f)


def load_texture(textures_dir, ref):
    # "nodewire:block/panel/knob" -> textures_dir/knob.png
    path = os.path.join(textures_dir, ref.split("/")[-1] + ".png")
    return Image.open(path).convert("RGBA")


# ── geometry ───────────────────────────────────────────────────────────────


def rot_axis(p, axis, deg, origin):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = (p[i] - origin[i] for i in range(3))
    if axis == "x":
        y, z = y * c - z * s, y * s + z * c
    elif axis == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return [x + origin[0], y + origin[1], z + origin[2]]


# Face corner order chosen so uv (u1,v1)-(u2,v2) maps naturally; minor mirror
# differences vs vanilla are fine for previewing orientation/scale.
FACE_CORNERS = {
    "up": lambda f, t: [(f[0], t[1], f[2]), (t[0], t[1], f[2]), (t[0], t[1], t[2]), (f[0], t[1], t[2])],
    "down": lambda f, t: [(f[0], f[1], t[2]), (t[0], f[1], t[2]), (t[0], f[1], f[2]), (f[0], f[1], f[2])],
    "north": lambda f, t: [(t[0], t[1], f[2]), (f[0], t[1], f[2]), (f[0], f[1], f[2]), (t[0], f[1], f[2])],
    "south": lambda f, t: [(f[0], t[1], t[2]), (t[0], t[1], t[2]), (t[0], f[1], t[2]), (f[0], f[1], t[2])],
    "west": lambda f, t: [(f[0], t[1], f[2]), (f[0], t[1], t[2]), (f[0], f[1], t[2]), (f[0], f[1], f[2])],
    "east": lambda f, t: [(t[0], t[1], t[2]), (t[0], t[1], f[2]), (t[0], f[1], f[2]), (t[0], f[1], t[2])],
}

FACE_SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}


def model_faces(model, textures_dir, offset=(0, 0, 0), pre=None):
    """Yield (corners3d[4], texture_img, uv, uv_rot, shade) for every face.

    [pre] is an optional transform applied to every point AFTER element
    rotation (part transforms: slide / press / turn), taking and returning a
    3-point.
    """
    tex_cache = {}
    for el in model.get("elements", []):
        f, t = el["from"], el["to"]
        rot = el.get("rotation")
        for face_name, face in el.get("faces", {}).items():
            corners = [list(c) for c in FACE_CORNERS[face_name](f, t)]
            if rot and rot.get("angle"):
                corners = [rot_axis(c, rot["axis"], rot["angle"], rot["origin"]) for c in corners]
            if pre:
                corners = [pre(c) for c in corners]
            corners = [[c[0] + offset[0], c[1] + offset[1], c[2] + offset[2]] for c in corners]
            ref = face["texture"].lstrip("#")
            ref = model["textures"].get(ref, ref)
            if ref not in tex_cache:
                tex_cache[ref] = load_texture(textures_dir, ref)
            uv = face.get("uv", [0, 0, 16, 16])
            yield corners, tex_cache[ref], uv, face.get("rotation", 0), FACE_SHADE[face_name]


# ── rendering ──────────────────────────────────────────────────────────────


def render(faces, out_path, yaw=0.0, pitch=90.0, px_per_unit=28, pad=8):
    """Orthographic render. pitch=90 => looking straight down Y (the player's
    front view of the panel); yaw/pitch in degrees tilt the camera."""
    ya, pa = math.radians(yaw), math.radians(pitch)

    def project(p):
        x, y, z = p
        # yaw about Y
        xr = x * math.cos(ya) + z * math.sin(ya)
        zr = -x * math.sin(ya) + z * math.cos(ya)
        # pitch: camera looks down at the XZ plane; pitch 90 = straight down.
        sy = zr * math.cos(pa) - y * math.sin(pa)  # screen y
        depth = zr * math.sin(pa) + y * math.cos(pa)
        return xr, sy, depth

    prj = []
    for corners, tex, uv, uv_rot, shade in faces:
        pts = [project(c) for c in corners]
        depth = sum(p[2] for p in pts) / 4
        prj.append((depth, [(p[0], p[1]) for p in pts], tex, uv, uv_rot, shade))
    prj.sort(key=lambda e: e[0])  # painter: far first (small depth = far below)

    xs = [p[0] for e in prj for p in e[1]]
    ys = [p[1] for e in prj for p in e[1]]
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    w = int((maxx - minx) * px_per_unit) + 2 * pad
    h = int((maxy - miny) * px_per_unit) + 2 * pad
    img = Image.new("RGBA", (max(w, 16), max(h, 16)), (24, 26, 30, 255))
    buf = img.load()

    def to_px(p):
        return ((p[0] - minx) * px_per_unit + pad, (p[1] - miny) * px_per_unit + pad)

    for depth, quad, tex, uv, uv_rot, shade in prj:
        p0, p1, p2, p3 = [to_px(p) for p in quad]
        # parallelogram basis: p = p0 + a*(p1-p0) + b*(p3-p0), a,b in [0,1]
        ax, ay = p1[0] - p0[0], p1[1] - p0[1]
        bx, by = p3[0] - p0[0], p3[1] - p0[1]
        det = ax * by - ay * bx
        if abs(det) < 1e-9:
            continue
        tw, th = tex.size
        su = tw / 16.0
        sv = th / 16.0
        bb_minx = int(min(p[0] for p in (p0, p1, p2, p3)))
        bb_maxx = int(max(p[0] for p in (p0, p1, p2, p3))) + 1
        bb_miny = int(min(p[1] for p in (p0, p1, p2, p3)))
        bb_maxy = int(max(p[1] for p in (p0, p1, p2, p3))) + 1
        tpix = tex.load()
        for py in range(max(0, bb_miny), min(img.height, bb_maxy)):
            for px_ in range(max(0, bb_minx), min(img.width, bb_maxx)):
                dx, dy = px_ + 0.5 - p0[0], py + 0.5 - p0[1]
                a = (dx * by - dy * bx) / det
                b = (ax * dy - ay * dx) / det
                if not (0 <= a <= 1 and 0 <= b <= 1):
                    continue
                # uv mapping with face rotation
                fa, fb = a, b
                for _ in range(int(uv_rot / 90) % 4):
                    fa, fb = fb, 1 - fa
                u = uv[0] + fa * (uv[2] - uv[0])
                v = uv[1] + fb * (uv[3] - uv[1])
                tx = min(tw - 1, max(0, int(u * su)))
                ty = min(th - 1, max(0, int(v * sv)))
                r, g, bcol, alpha = tpix[tx, ty]
                if alpha < 8:
                    continue
                buf[px_, py] = (int(r * shade), int(g * shade), int(bcol * shade), 255)
    img.save(out_path)
    return out_path


# ── scenes ─────────────────────────────────────────────────────────────────


def main():
    models_dir, textures_dir, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out_dir, exist_ok=True)

    def m(name):
        return load_model(models_dir, name)

    def slide_z(dz):
        return lambda p: [p[0], p[1], p[2] + dz]

    def press_y(dy):
        return lambda p: [p[0], p[1] + dy, p[2]]

    def turn_y(deg, cx, cz):
        return lambda p: rot_axis(p, "y", deg, [cx, 0, cz])

    scenes = {
        # name: list of (model, offset, pre-transform)
        "lever_off": [(m("lever_base"), (0, 0, 0), None), (m("lever_handle"), (0, 0, 0), None)],
        "lever_on": [(m("lever_base"), (0, 0, 0), None), (m("lever_handle"), (0, 0, 0), slide_z(4))],
        "momentary_off": [(m("momentary_base"), (0, 0, 0), None), (m("momentary_button"), (0, 0, 0), None)],
        "momentary_on": [(m("momentary_base"), (0, 0, 0), None), (m("momentary_button"), (0, 0, 0), press_y(-0.5))],
        "bulb_off": [(m("bulb_base"), (0, 0, 0), None), (m("bulb_off"), (0, 0, 0), None)],
        "bulb_on": [(m("bulb_base"), (0, 0, 0), None), (m("bulb_on"), (0, 0, 0), None)],
        "knob_0": [(m("knob"), (0, 0, 0), turn_y(-45, 1, 1))],
        "knob_mid": [(m("knob"), (0, 0, 0), turn_y(90, 1, 1))],
        "plate": [(m("panel_plate"), (0, 0, 0), None)],
        "panel_combo": [
            (m("panel_plate"), (0, 0, 0), None),
            (m("lever_base"), (1, 0, 1), None),
            (m("lever_handle"), (1, 0, 1), None),
            (m("momentary_base"), (6, 0, 1), None),
            (m("momentary_button"), (6, 0, 1), None),
            (m("bulb_base"), (11, 0, 1), None),
            (m("bulb_on"), (11, 0, 1), None),
            (m("knob"), (6, 0, 7), turn_y(60, 1, 1)),
        ],
    }

    for name, parts in scenes.items():
        faces = []
        for model, offset, pre in parts:
            faces.extend(model_faces(model, textures_dir, offset, pre))
        # front view (player looking at the panel) + tilted view (depth cues)
        render(faces, os.path.join(out_dir, f"{name}_front.png"), yaw=0, pitch=90, px_per_unit=30)
        render(faces, os.path.join(out_dir, f"{name}_iso.png"), yaw=30, pitch=55, px_per_unit=30)
        print("rendered", name)


if __name__ == "__main__":
    main()
