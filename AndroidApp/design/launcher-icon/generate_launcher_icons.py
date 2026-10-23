#!/usr/bin/env python3
"""
T-Emulator Android Launcher Icon Generator & Optimizer
Generates SVG sources, Android VectorDrawables, legacy raster bitmaps,
monochrome themed icons, and verification preview documents.
"""

import os
import io
import resvg_py
from PIL import Image, ImageDraw

# Color constants
COLOR_BG = (12, 118, 254, 255)        # #0C76FE app brand blue
COLOR_BODY = "#FFFFFF"                  # Crisp controller face
COLOR_BODY_SHADOW = "#CBD5E1"           # Subtle 3D bottom rim
COLOR_BUMPERS = "#2E3A48"               # L/R triggers (slate-800)
COLOR_DPAD = "#1E293B"                  # D-pad cross (slate-900)
COLOR_DPAD_CENTER = "#0F172A"           # D-pad center pivot dot
COLOR_RECESS = "#F1F5F9"                # Subtle recessed wells for dpad/buttons
COLOR_BTN_BLUE = "#0C76FE"              # X Button (app signature blue)
COLOR_BTN_RED = "#EF4444"               # A Button (vibrant red)
COLOR_BTN_YELLOW = "#F59E0B"            # B Button (amber/yellow)
COLOR_BTN_GREEN = "#10B981"             # Y Button (emerald green)
COLOR_PILLS = "#64748B"                 # Select & Start buttons (slate-500)

# Paths for controller geometry (Canvas: 108x108, Center: 54, 54, Width: 60dp)
PATH_BODY = (
    "M 54.0,42.2 "
    "C 58.5,42.2 63.5,42.0 67.5,41.2 "
    "C 72.0,40.3 76.5,41.8 79.5,45.2 "
    "C 82.8,49.0 84.0,55.0 84.0,61.0 "
    "C 84.0,66.0 80.5,69.5 75.5,69.5 "
    "C 70.8,69.5 67.2,65.8 64.5,61.8 "
    "C 62.0,58.2 58.2,56.8 54.0,56.8 "
    "C 49.8,56.8 46.0,58.2 43.5,61.8 "
    "C 40.8,65.8 37.2,69.5 32.5,69.5 "
    "C 27.5,69.5 24.0,66.0 24.0,61.0 "
    "C 24.0,55.0 25.2,49.0 28.5,45.2 "
    "C 31.5,41.8 36.0,40.3 40.5,41.2 "
    "C 44.5,42.0 49.5,42.2 54.0,42.2 Z"
)

PATH_BODY_SHADOW = (
    "M 54.0,42.9 "
    "C 58.5,42.9 63.5,42.7 67.5,41.9 "
    "C 72.0,41.0 76.5,42.5 79.5,45.9 "
    "C 82.8,49.7 84.0,55.7 84.0,61.7 "
    "C 84.0,66.7 80.5,70.2 75.5,70.2 "
    "C 70.8,70.2 67.2,66.5 64.5,62.5 "
    "C 62.0,58.9 58.2,57.5 54.0,57.5 "
    "C 49.8,57.5 46.0,58.9 43.5,62.5 "
    "C 40.8,66.5 37.2,70.2 32.5,70.2 "
    "C 27.5,70.2 24.0,66.7 24.0,61.7 "
    "C 24.0,55.7 25.2,49.7 28.5,45.9 "
    "C 31.5,42.5 36.0,41.0 40.5,41.9 "
    "C 44.5,42.7 49.5,42.9 54.0,42.9 Z"
)

PATH_LEFT_BUMPER = "M 31.0,43.0 C 31.0,39.5 34.0,37.5 37.5,37.5 C 41.0,37.5 44.0,39.5 44.0,43.0 Z"
PATH_RIGHT_BUMPER = "M 64.0,43.0 C 64.0,39.5 67.0,37.5 70.5,37.5 C 74.0,37.5 77.0,39.5 77.0,43.0 Z"

# Helper for SVG/VectorDrawable paths
def circle_path(cx, cy, r):
    return f"M {cx-r:.2f},{cy:.2f} A {r:.2f},{r:.2f} 0 1,0 {cx+r:.2f},{cy:.2f} A {r:.2f},{r:.2f} 0 1,0 {cx-r:.2f},{cy:.2f} Z"

def round_rect_path(x, y, w, h, rx):
    return (
        f"M {x+rx:.2f},{y:.2f} "
        f"L {x+w-rx:.2f},{y:.2f} A {rx:.2f},{rx:.2f} 0 0,1 {x+w:.2f},{y+rx:.2f} "
        f"L {x+w:.2f},{y+h-rx:.2f} A {rx:.2f},{rx:.2f} 0 0,1 {x+w-rx:.2f},{y+h:.2f} "
        f"L {x+rx:.2f},{y+h:.2f} A {rx:.2f},{rx:.2f} 0 0,1 {x:.2f},{y+h-rx:.2f} "
        f"L {x:.2f},{y+rx:.2f} A {rx:.2f},{rx:.2f} 0 0,1 {x+rx:.2f},{y:.2f} Z"
    )

# D-pad components
PATH_DPAD_H = round_rect_path(30.0, 51.1, 12.0, 4.8, 1.0)
PATH_DPAD_V = round_rect_path(33.6, 47.5, 4.8, 12.0, 1.0)
PATH_DPAD_DOT = circle_path(36.0, 53.5, 1.3)

# Action buttons
PATH_BTN_TOP = circle_path(72.0, 48.9, 2.4)     # X (Blue)
PATH_BTN_RIGHT = circle_path(76.6, 53.5, 2.4)   # A (Red)
PATH_BTN_BOTTOM = circle_path(72.0, 58.1, 2.4)  # B (Yellow)
PATH_BTN_LEFT = circle_path(67.4, 53.5, 2.4)    # Y (Green)

# Select / Start buttons
PATH_SELECT = "M 48.0,52.8 L 51.5,53.8 A 0.9,0.9 0 0,1 51.0,55.4 L 47.5,54.4 A 0.9,0.9 0 0,1 48.0,52.8 Z"
PATH_START = "M 53.8,52.8 L 57.3,53.8 A 0.9,0.9 0 0,1 56.8,55.4 L 53.3,54.4 A 0.9,0.9 0 0,1 53.8,52.8 Z"

def build_full_svg(theme='color'):
    if theme == 'color':
        return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <!-- Bumpers behind controller body -->
  <path d="{PATH_LEFT_BUMPER}" fill="{COLOR_BUMPERS}" />
  <path d="{PATH_RIGHT_BUMPER}" fill="{COLOR_BUMPERS}" />

  <!-- Subtle bottom rim for depth -->
  <path d="{PATH_BODY_SHADOW}" fill="{COLOR_BODY_SHADOW}" />

  <!-- Controller main white face -->
  <path d="{PATH_BODY}" fill="{COLOR_BODY}" />

  <!-- D-Pad well accent -->
  <path d="{circle_path(36.0, 53.5, 8.0)}" fill="{COLOR_RECESS}" />

  <!-- Action buttons well accent -->
  <path d="{circle_path(72.0, 53.5, 8.0)}" fill="{COLOR_RECESS}" />

  <!-- D-Pad cross -->
  <path d="{PATH_DPAD_H}" fill="{COLOR_DPAD}" />
  <path d="{PATH_DPAD_V}" fill="{COLOR_DPAD}" />
  <path d="{PATH_DPAD_DOT}" fill="{COLOR_DPAD_CENTER}" />

  <!-- Action Buttons (SNES layout) -->
  <path d="{PATH_BTN_TOP}" fill="{COLOR_BTN_BLUE}" />
  <path d="{PATH_BTN_RIGHT}" fill="{COLOR_BTN_RED}" />
  <path d="{PATH_BTN_BOTTOM}" fill="{COLOR_BTN_YELLOW}" />
  <path d="{PATH_BTN_LEFT}" fill="{COLOR_BTN_GREEN}" />

  <!-- Select and Start buttons -->
  <path d="{PATH_SELECT}" fill="{COLOR_PILLS}" />
  <path d="{PATH_START}" fill="{COLOR_PILLS}" />
</svg>'''
    else: # monochrome silhouette with cutouts
        cutouts = f"{PATH_DPAD_H} {PATH_DPAD_V} {PATH_BTN_TOP} {PATH_BTN_RIGHT} {PATH_BTN_BOTTOM} {PATH_BTN_LEFT} {PATH_SELECT} {PATH_START}"
        return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">
  <path d="{PATH_LEFT_BUMPER}" fill="#FFFFFF" />
  <path d="{PATH_RIGHT_BUMPER}" fill="#FFFFFF" />
  <path d="{PATH_BODY} {cutouts}" fill="#FFFFFF" fill-rule="evenodd" />
</svg>'''

def build_vectordrawable(theme='color'):
    if theme == 'color':
        return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    
    <!-- Bumpers behind controller body -->
    <path
        android:fillColor="{COLOR_BUMPERS}"
        android:pathData="{PATH_LEFT_BUMPER}" />
    <path
        android:fillColor="{COLOR_BUMPERS}"
        android:pathData="{PATH_RIGHT_BUMPER}" />

    <!-- Subtle bottom rim for depth -->
    <path
        android:fillColor="{COLOR_BODY_SHADOW}"
        android:pathData="{PATH_BODY_SHADOW}" />

    <!-- Controller main white face -->
    <path
        android:fillColor="{COLOR_BODY}"
        android:pathData="{PATH_BODY}" />

    <!-- D-Pad well accent -->
    <path
        android:fillColor="{COLOR_RECESS}"
        android:pathData="{circle_path(36.0, 53.5, 8.0)}" />

    <!-- Action buttons well accent -->
    <path
        android:fillColor="{COLOR_RECESS}"
        android:pathData="{circle_path(72.0, 53.5, 8.0)}" />

    <!-- D-Pad cross -->
    <path
        android:fillColor="{COLOR_DPAD}"
        android:pathData="{PATH_DPAD_H}" />
    <path
        android:fillColor="{COLOR_DPAD}"
        android:pathData="{PATH_DPAD_V}" />
    <path
        android:fillColor="{COLOR_DPAD_CENTER}"
        android:pathData="{PATH_DPAD_DOT}" />

    <!-- Action Buttons (SNES layout) -->
    <path
        android:fillColor="{COLOR_BTN_BLUE}"
        android:pathData="{PATH_BTN_TOP}" />
    <path
        android:fillColor="{COLOR_BTN_RED}"
        android:pathData="{PATH_BTN_RIGHT}" />
    <path
        android:fillColor="{COLOR_BTN_YELLOW}"
        android:pathData="{PATH_BTN_BOTTOM}" />
    <path
        android:fillColor="{COLOR_BTN_GREEN}"
        android:pathData="{PATH_BTN_LEFT}" />

    <!-- Select and Start buttons -->
    <path
        android:fillColor="{COLOR_PILLS}"
        android:pathData="{PATH_SELECT}" />
    <path
        android:fillColor="{COLOR_PILLS}"
        android:pathData="{PATH_START}" />
</vector>
'''
    else: # monochrome for themed icons
        cutouts = f"{PATH_DPAD_H} {PATH_DPAD_V} {PATH_BTN_TOP} {PATH_BTN_RIGHT} {PATH_BTN_BOTTOM} {PATH_BTN_LEFT} {PATH_SELECT} {PATH_START}"
        return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="{PATH_LEFT_BUMPER}" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="{PATH_RIGHT_BUMPER}" />
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="{PATH_BODY} {cutouts}" />
</vector>
'''

def render_svg_to_image(svg_content, size):
    scaled = svg_content.replace('width="108" height="108"', f'width="{size}" height="{size}"')
    png_bytes = resvg_py.svg_to_bytes(scaled)
    return Image.open(io.BytesIO(png_bytes)).convert('RGBA')

def generate_legacy_bitmaps(svg_content, output_base_dir):
    # Density table:
    # mdpi: 48x48
    # hdpi: 72x72
    # xhdpi: 96x96
    # xxhdpi: 144x144
    # xxxhdpi: 192x192
    densities = {
        'mipmap-mdpi': 48,
        'mipmap-hdpi': 72,
        'mipmap-xhdpi': 96,
        'mipmap-xxhdpi': 144,
        'mipmap-xxxhdpi': 192
    }
    
    for density_name, sz in densities.items():
        # High quality rendering at 4x then downscale with Lanczos for crisp antialiasing
        render_sz = sz * 4
        # Adaptive canvas is 108dp, launcher icon viewport is central 72dp (ratio = 72/108 = 2/3)
        # So full canvas render size:
        canvas_sz = int(render_sz * 108 / 72)
        fg_full = render_svg_to_image(svg_content, canvas_sz)
        
        # Crop central 72dp viewport
        margin = int(18 / 108 * canvas_sz)
        fg_crop = fg_full.crop((margin, margin, canvas_sz - margin, canvas_sz - margin))
        
        # 1. ic_launcher.png (Rounded square on #0C76FE)
        # Radius ~22% of size (standard Android legacy icon squircle/rounded rect)
        mask_rs = Image.new('L', (render_sz, render_sz), 0)
        draw_rs = ImageDraw.Draw(mask_rs)
        draw_rs.rounded_rectangle((0, 0, render_sz, render_sz), radius=int(render_sz * 0.22), fill=255)
        
        bg_rs = Image.new('RGBA', (render_sz, render_sz), COLOR_BG)
        bg_rs.alpha_composite(fg_crop)
        
        icon_rs = Image.new('RGBA', (render_sz, render_sz), (0, 0, 0, 0))
        icon_rs.paste(bg_rs, (0, 0), mask_rs)
        icon_rs = icon_rs.resize((sz, sz), Image.Resampling.LANCZOS)
        
        # 2. ic_launcher_round.png (Circle on #0C76FE)
        mask_rd = Image.new('L', (render_sz, render_sz), 0)
        draw_rd = ImageDraw.Draw(mask_rd)
        draw_rd.ellipse((0, 0, render_sz, render_sz), fill=255)
        
        bg_rd = Image.new('RGBA', (render_sz, render_sz), COLOR_BG)
        bg_rd.alpha_composite(fg_crop)
        
        icon_rd = Image.new('RGBA', (render_sz, render_sz), (0, 0, 0, 0))
        icon_rd.paste(bg_rd, (0, 0), mask_rd)
        icon_rd = icon_rd.resize((sz, sz), Image.Resampling.LANCZOS)
        
        # Save to mipmap folder
        target_dir = os.path.join(output_base_dir, density_name)
        os.makedirs(target_dir, exist_ok=True)
        
        icon_rs.save(os.path.join(target_dir, 'ic_launcher.png'), 'PNG')
        icon_rd.save(os.path.join(target_dir, 'ic_launcher_round.png'), 'PNG')
        print(f"Generated {density_name}: ic_launcher.png ({sz}x{sz}), ic_launcher_round.png ({sz}x{sz})")

def main():
    print("=== Generating T-Emulator Launcher Icons ===")
    
    # Paths
    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
    design_dir = os.path.join(project_root, "design", "launcher-icon")
    res_dir = os.path.join(project_root, "app", "src", "main", "res")
    docs_icon_dir = os.path.join(project_root, "docs", "launcher-icon")
    
    os.makedirs(design_dir, exist_ok=True)
    os.makedirs(os.path.join(res_dir, "drawable"), exist_ok=True)
    os.makedirs(docs_icon_dir, exist_ok=True)
    
    # 1. Master SVG source
    svg_color = build_full_svg(theme='color')
    svg_path = os.path.join(design_dir, "controller.svg")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(svg_color)
    print(f"1. Saved Master SVG: {svg_path}")

    # Monochrome Master SVG
    svg_mono = build_full_svg(theme='mono')
    svg_mono_path = os.path.join(design_dir, "controller_monochrome.svg")
    with open(svg_mono_path, "w", encoding="utf-8") as f:
        f.write(svg_mono)
    print(f"   Saved Monochrome SVG: {svg_mono_path}")

    # 2. VectorDrawable foreground
    vd_color = build_vectordrawable(theme='color')
    vd_path = os.path.join(res_dir, "drawable", "ic_launcher_foreground.xml")
    with open(vd_path, "w", encoding="utf-8") as f:
        f.write(vd_color)
    print(f"2. Saved VectorDrawable: {vd_path}")

    # Monochrome VectorDrawable
    vd_mono = build_vectordrawable(theme='mono')
    vd_mono_path = os.path.join(res_dir, "drawable", "ic_launcher_monochrome.xml")
    with open(vd_mono_path, "w", encoding="utf-8") as f:
        f.write(vd_mono)
    print(f"   Saved Monochrome VectorDrawable: {vd_mono_path}")

    # 3. Update mipmap-anydpi-v26 adaptive XMLs
    v26_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
    os.makedirs(v26_dir, exist_ok=True)
    
    adaptive_xml_v26 = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''
    with open(os.path.join(v26_dir, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml_v26)
    with open(os.path.join(v26_dir, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml_v26)
    print("3. Updated mipmap-anydpi-v26/ic_launcher.xml and ic_launcher_round.xml")

    # 4. Create mipmap-anydpi-v33 adaptive XMLs (with monochrome themed icon)
    v33_dir = os.path.join(res_dir, "mipmap-anydpi-v33")
    os.makedirs(v33_dir, exist_ok=True)
    
    adaptive_xml_v33 = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
'''
    with open(os.path.join(v33_dir, "ic_launcher.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml_v33)
    with open(os.path.join(v33_dir, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
        f.write(adaptive_xml_v33)
    print("4. Created mipmap-anydpi-v33/ic_launcher.xml and ic_launcher_round.xml (Themed icon)")

    # 5. Remove obsolete ic_launcher_foreground.png files
    densities = ['mipmap-mdpi', 'mipmap-hdpi', 'mipmap-xhdpi', 'mipmap-xxhdpi', 'mipmap-xxxhdpi']
    for d in densities:
        old_fg = os.path.join(res_dir, d, "ic_launcher_foreground.png")
        if os.path.exists(old_fg):
            os.remove(old_fg)
            print(f"5. Removed obsolete raster foreground: {old_fg}")

    # 6. Generate synchronized legacy bitmaps across 5 densities
    print("6. Generating synchronized legacy bitmaps...")
    generate_legacy_bitmaps(svg_color, res_dir)

    print("\n=== Generation Complete! ===")

if __name__ == "__main__":
    main()
