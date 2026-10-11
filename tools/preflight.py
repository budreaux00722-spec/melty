#!/usr/bin/env python3
"""Preflight: lay every sheet over the others and list unfilled cells and
references that do not resolve. Exit code 1 if anything is open.

A cell is filled when it is not empty/None. "-" means "not applicable" and
counts as filled. References look like "sheet:id"; other known reference
columns are checked by name (recipeRef, descriptorRef, testedBy, javaClass).
"""
import json, os, re, sys, glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHEETS = os.path.join(ROOT, "design", "sheets")
JAVA = os.path.join(ROOT, "mod", "src")

def load():
    out = {}
    for p in sorted(glob.glob(os.path.join(SHEETS, "*.json"))):
        d = json.load(open(p, encoding="utf-8"))
        out[d["sheet"]] = d
    return out

def main():
    sheets = load()
    ids = {name: {r["id"] for r in s["rows"]} for name, s in sheets.items()}
    problems, warnings = [], []
    java_classes = set()
    for p in glob.glob(os.path.join(JAVA, "**", "*.java"), recursive=True):
        java_classes.add(os.path.splitext(os.path.basename(p))[0])

    def ref(sheet, rid, col, target, value):
        if value == "-":
            return
        if value not in ids.get(target, set()):
            problems.append(f"{sheet}:{rid}.{col} -> {target}:{value} does not resolve")

    for name, s in sheets.items():
        cols = s["columns"]
        seen = set()
        for r in s["rows"]:
            rid = r.get("id", "?")
            if rid in seen:
                problems.append(f"{name}: duplicate id {rid}")
            seen.add(rid)
            for c in cols:
                v = r.get(c)
                if v is None or v == "" or v == []:
                    problems.append(f"{name}:{rid}.{c} is unfilled")
            for c in r:
                if c not in cols:
                    problems.append(f"{name}:{rid}.{c} is not a declared column")
            for c, v in r.items():
                vals = v if isinstance(v, list) else [v]
                for x in vals:
                    if isinstance(x, str) and re.fullmatch(r"[a-z_]+:[a-z0-9_]+", x) and x.split(":")[0] in sheets:
                        ref(name, rid, c, x.split(":")[0], x.split(":")[1])
            if "recipeRef" in r: ref(name, rid, "recipeRef", "recipes", r["recipeRef"])
            if "descriptorRef" in r: ref(name, rid, "descriptorRef", "nms_content", r["descriptorRef"])
            if "testedBy" in r: ref(name, rid, "testedBy", "tests", r["testedBy"])
            if name == "recipes": ref(name, rid, "result", "parts", r["result"])
            if name == "wreck": ref(name, rid, "part", "parts", r["part"])
            jc = r.get("javaClass") or r.get("readerClass")
            if jc and jc != "-" and jc not in java_classes:
                problems.append(f"{name}:{rid} names class {jc}, which is not in mod/src yet")
            if name == "tests" and r.get("status") not in ("passed",):
                warnings.append(f"tests:{rid} is '{r.get('status')}' (not yet verified)")

    for p in problems: print("OPEN  ", p)
    for w in warnings: print("VERIFY", w)
    print(f"\n{len(problems)} open cell(s)/reference(s), {len(warnings)} unverified test(s).")
    sys.exit(1 if problems else 0)

if __name__ == "__main__":
    main()
