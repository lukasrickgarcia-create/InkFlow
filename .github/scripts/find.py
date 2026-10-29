import re, sys, xml.etree.ElementTree as ET
path, want = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
best = None
for n in root.iter("node"):
    t = (n.get("text") or "") + "|" + (n.get("content-desc") or "")
    if want in t:
        m = re.findall(r"\d+", n.get("bounds") or "")
        if len(m) == 4:
            x1, y1, x2, y2 = map(int, m)
            exact = want == (n.get("text") or "") or want == (n.get("content-desc") or "")
            if best is None or exact:
                best = ((x1 + x2) // 2, (y1 + y2) // 2)
                if exact:
                    break
if best:
    print(best[0], best[1])
