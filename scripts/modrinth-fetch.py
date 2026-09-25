#!/usr/bin/env python3
"""Download Fabric mods for a Minecraft version from Modrinth, with their
required dependencies.

Usage: modrinth-fetch.py <minecraft-version> <dest-dir> <mod>...

Each <mod> is "slug[@version][=Display Name]". "@version" picks that
version_number (exact, else the first containing it) instead of the
newest; "=Display Name" is searched when the slug has no Fabric build
for this Minecraft version. Pinned mods listed before a mod that needs
them take the place of the newest version as its dependency.

Prints one line per mod: "loaded <mod> <version>" or "missing <mod>".
Fabric API is skipped: the dev runtime already has it. Exits 0 even when
some mods are missing, so a bench can report what it ran with.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

API = "https://api.modrinth.com/v2"
FABRIC_API = {"P7dR8mSH", "fabric-api"}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Ferrite-CI (github.com/pokeyt59/Ferritey)"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def latest(project, mc, want=""):
    q = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([mc])})
    try:
        versions = get(f"{API}/project/{project}/version?{q}")
    except Exception as e:  # unknown project, network error
        print(f"error {project} {e}", file=sys.stderr)
        return None
    if not versions:
        return None
    if want:
        exact = [v for v in versions if v["version_number"] == want]
        near = [v for v in versions if want in v["version_number"]]
        if exact or near:
            return (exact or near)[0]
        print(f"note {project}: version {want} not found, using {versions[0]['version_number']}", file=sys.stderr)
    return versions[0]


def search(name, mc):
    """Slug of the top Fabric search hit for this version, or None."""
    facets = json.dumps([["categories:fabric"], [f"versions:{mc}"]])
    q = urllib.parse.urlencode({"query": name, "facets": facets, "limit": 5})
    try:
        hits = get(f"{API}/search?{q}").get("hits", [])
    except Exception as e:
        print(f"error search {name} {e}", file=sys.stderr)
        return None
    key = name.lower().replace(" ", "").replace("'", "")
    for h in hits:
        if key in h["title"].lower().replace(" ", "").replace("'", ""):
            return h["slug"]
    return None


def main():
    mc, dest, mods = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(dest, exist_ok=True)
    done = set()
    queue = []
    jars = {}        # label -> downloaded jar
    parent = {}      # dependency label -> label of the mod that needs it
    for m in mods:
        spec, _, name = m.partition("=")
        slug, _, want = spec.partition("@")
        queue.append((slug, m, name, want))
    while queue:
        project, label, name, want = queue.pop(0)
        if project in done or project in FABRIC_API:
            continue
        done.add(project)
        v = latest(project, mc, want)
        if v is None and name:
            found = search(name, mc)
            if found and found not in done:
                done.add(found)
                v = latest(found, mc, want)
        if v is None:
            print(f"missing {label}")
            # A mod whose required dependency is missing would stop the
            # server at boot; drop it (and whoever needed it) instead.
            p = parent.get(label)
            while p is not None:
                if p in jars and os.path.exists(jars[p]):
                    os.remove(jars[p])
                    print(f"dropped {p} (missing dependency)")
                p = parent.get(p)
            continue
        done.add(v["project_id"])
        f = next((f for f in v["files"] if f.get("primary")), v["files"][0])
        path = os.path.join(dest, f["filename"])
        req = urllib.request.Request(f["url"], headers={"User-Agent": "Ferrite-CI"})
        with urllib.request.urlopen(req, timeout=120) as r, open(path, "wb") as out:
            out.write(r.read())
        jars[label] = path
        print(f"loaded {label} {v['version_number']}")
        for dep in v.get("dependencies", []):
            if dep.get("dependency_type") == "required" and dep.get("project_id"):
                pid = dep["project_id"]
                if pid not in done and pid not in FABRIC_API:
                    dep_label = f"{label.partition('=')[0]}->dependency:{pid}"
                    parent[dep_label] = label
                    queue.append((pid, dep_label, "", ""))


if __name__ == "__main__":
    main()
