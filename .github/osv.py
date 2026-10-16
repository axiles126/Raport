#!/usr/bin/env python3
"""Reads `gradle dependencies` output, queries OSV for every resolved Maven coordinate, prints known vulnerabilities."""
import json, re, sys, urllib.request

deps = set()
for line in open(sys.argv[1], encoding="utf-8", errors="ignore"):
    m = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?", line)
    if m:
        g, a, v, r = m.groups()
        deps.add((g, a, r or v))
deps = sorted(deps)
queries = [{"package": {"name": f"{g}:{a}", "ecosystem": "Maven"}, "version": v} for g, a, v in deps]
found = []
for i in range(0, len(queries), 500):
    req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps({"queries": queries[i:i+500]}).encode(),
                                 headers={"Content-Type": "application/json"})
    res = json.load(urllib.request.urlopen(req, timeout=60))["results"]
    for d, r in zip(deps[i:i+500], res):
        for v in r.get("vulns", []):
            found.append((d, v["id"]))
print(f"dependencies checked: {len(deps)}; vulnerabilities: {len(found)}")
for (g, a, v), vid in found:
    try:
        info = json.load(urllib.request.urlopen(f"https://api.osv.dev/v1/vulns/{vid}", timeout=30))
        sev = ",".join(s.get("score", "") for s in info.get("severity", []))[:60]
        print(f"{g}:{a}:{v}  {vid}  {sev}  {info.get('summary', '')[:140]}")
    except Exception as e:
        print(f"{g}:{a}:{v}  {vid}  (details: {e})")
