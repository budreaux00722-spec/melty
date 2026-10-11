#!/usr/bin/env python3
"""Builds the two release files in dist/:
  scrapsky-<v>.jar               the mod (installed into the instance's mods folder)
  ScrapSky-Minecraft-<v>.zip     portable Prism Launcher + the ScrapSky instance (Fabric named in
                                 mmc-pack.json, installed by Prism on first start) + Fabric API + e4mc
Usage: package.py <prism-portable.zip> <fabric-api.jar> <e4mc.jar> <prism-LICENSE>"""
import os, sys, zipfile, shutil, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
props = dict(l.strip().split("=", 1) for l in open(os.path.join(ROOT, "mod/gradle.properties")) if "=" in l and not l.startswith("#"))
V, MC, LOADER = props["mod_version"], props["minecraft_version"], props["loader_version"]
LWJGL = {"1.21.1": "3.3.3"}[MC]
prism_zip, fapi, e4mc, prism_license = sys.argv[1:5]
dist = os.path.join(ROOT, "dist")
os.makedirs(dist, exist_ok=True)

jar = os.path.join(ROOT, f"mod/build/libs/scrapsky-{V}.jar")
shutil.copy(jar, os.path.join(dist, f"scrapsky-{V}.jar"))

out = os.path.join(dist, f"ScrapSky-Minecraft-{V}.zip")
inst = "Prism/instances/ScrapSky"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    with zipfile.ZipFile(prism_zip) as p:
        for info in p.infolist():
            if info.is_dir(): continue
            name = info.filename
            if re.search(r"(^|/)accounts\.json$", name): continue  # never ship a sign-in
            z.writestr("Prism/" + name, p.read(info))
    z.write(prism_license, "Prism/LICENSE-PrismLauncher.txt")
    z.writestr("Prism/SOURCE-PrismLauncher.txt", "Prism Launcher 11.1.1, unmodified, GPL-3.0.\nSource: https://github.com/PrismLauncher/PrismLauncher/tree/11.1.1\n")
    z.write(os.path.join(ROOT, "packaging/instance/instance.cfg"), f"{inst}/instance.cfg")
    pack = open(os.path.join(ROOT, "packaging/instance/mmc-pack.json.in")).read().replace("@MC@", MC).replace("@LOADER@", LOADER).replace("@LWJGL@", LWJGL)
    z.writestr(f"{inst}/mmc-pack.json", pack)
    z.write(fapi, f"{inst}/.minecraft/mods/{os.path.basename(fapi)}")
    z.write(e4mc, f"{inst}/.minecraft/mods/{os.path.basename(e4mc)}")
    z.writestr(f"{inst}/.minecraft/config/scrapsky/melty.properties", "# Melty fills join= with a host's address to join their game; empty = host\njoin=\n")
    z.write(os.path.join(ROOT, "CREDITS.md"), f"{inst}/SCRAPSKY-CREDITS.md")
print(out, os.path.getsize(out))
