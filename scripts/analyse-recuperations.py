#!/usr/bin/env python3
"""Port of android/.../Intervals.kt to check the detection of recoveries against what Eloi writes in his Strava names.

usage: analyse-recuperations.py foulee.db [--verbose] [--ancienne]
The db is the app database of a debug build: adb exec-out "run-as fr.foulee.app cat databases/foulee.db" > foulee.db
Keep the rule here and the one in Intervals.kt (isMeasuredGroup) identical.
"""
import json, math, re, sqlite3, sys
from statistics import mean, median

MIN_GAP = 1.12
SAME_DISTANCE = 0.15


def cv(v):
    m = mean(v)
    return math.sqrt(mean([(x - m) ** 2 for x in v])) / m if m else 0.0


def rnd(x):  # Kotlin roundToInt: half up
    return int(math.floor(x + 0.5))


def round_step(d):
    return 500 if d >= 2950 else 100 if d >= 950 else 50 if d >= 150 else 10


def round_distance(d):
    s = round_step(d)
    return rnd(d / s) * s


def on_round_distance(d):
    r = round_distance(d)
    return r > 0 and abs(d - r) / r <= 0.025


def nominal_distance(d):
    r = round_distance(d)
    return f"{r / 1000:.1f} km".replace(".0", "") if r >= 3000 else f"{r} m"


def nominal_duration(s):
    r = rnd(s / 5) * 5
    m, sec = divmod(r, 60)
    if m == 0:
        return f"{sec} s"
    return f"{m} min {sec:02d}" if sec else f"{m} min"


def nearest_multiple(v, step):
    return rnd(v / step) * step


class Lap:
    def __init__(self, a):
        self.distance, self.duration, self.speed, self.hr, self.maxhr, self.elev, self.intensity, self.step = a
        self.role = None
        self.kind = None


def is_tiny(l):
    return (l.distance or 0) < 30 and (l.duration or 0) < 15


def speed_of(l):
    return l.distance / l.duration if (l.duration or 0) > 0 else 0.0


def structured_roles(laps):
    if all(l.intensity is None for l in laps):
        return None
    m = {"WARMUP": "W", "COOLDOWN": "C", "REST": "R", "RECOVERY": "R", "ACTIVE": "E", "INTERVAL": "E"}
    roles = ["O" if is_tiny(l) else m.get(l.intensity, "O") for l in laps]
    if roles.count("E") < 2:
        return None
    # Some watches tag the pause between two efforts "cooldown" (or "warmup"): between two efforts it is a recovery
    first, last = roles.index("E"), len(roles) - 1 - roles[::-1].index("E")
    return ["R" if first < i < last and r in ("C", "W") else r for i, r in enumerate(roles)]


def inferred_roles(laps):
    cands = [l for l in laps if not is_tiny(l) and speed_of(l) > 0]
    speeds = sorted(set(speed_of(l) for l in cands), reverse=True)
    thr = 0.0
    best = 0.0
    for i in range(len(speeds) - 1):
        g = speeds[i] / speeds[i + 1]
        if g > best:
            best = g
            thr = (speeds[i] + speeds[i + 1]) / 2
    fast = sum(1 for l in cands if speed_of(l) > thr)
    if best < MIN_GAP or fast < 2 or fast == len(cands):
        return None
    roles = ["O" if is_tiny(l) else "E" if speed_of(l) > thr else "R" for l in laps]
    first = roles.index("E")
    last = len(roles) - 1 - roles[::-1].index("E")
    return [r if r != "R" else "W" if i < first else "C" if i > last else "R" for i, r in enumerate(roles)]


def is_round_time(t):
    return t >= 15 and abs(t - nearest_multiple(t, 15)) <= 1.5  # former rule, kept for the 'app' comparison


def is_round_measure(d):
    return d >= 50 and abs(d - round_distance(d)) <= max(3.0, 0.02 * round_distance(d))


def nearest50(d):
    return max(50, rnd(d / 50) * 50)


def fit_time(l):
    return abs(l.duration - nearest_multiple(l.duration, 15)) <= 3.5


def fit_dist(l):
    return l.distance >= 70 and -0.1 <= (l.distance - nearest50(l.distance)) / nearest50(l.distance) <= 0.15


def measured_group(ls):
    """Timed or measured, for the recoveries of one group of efforts (same rule as Intervals.isMeasuredGroup)."""
    if len(ls) == 1:
        return False  # a single lap proves nothing, and timed is the usual case
    tf = sum(1 for l in ls if fit_time(l)) / len(ls)
    df = sum(1 for l in ls if fit_dist(l)) / len(ls)
    if df >= 0.75 and tf < 0.5:
        return True
    if tf >= 0.5 and df < 0.75:
        return False
    if len(ls) >= 3:
        d = [l.distance for l in ls]
        return cv(d) < 0.07 and cv(d) < cv([l.duration for l in ls]) * 0.7
    return False


def efforts_group(laps, roles, tagged):
    """Group index of each effort lap (same bucketing as the app)."""
    buckets, of = [], {}
    for i, l in enumerate(laps):
        if roles[i] != "E":
            continue
        d, step = l.distance, (l.step if tagged else None)
        b = None
        for k, bk in enumerate(buckets):
            if step is not None:
                if bk["step"] == step:
                    b = k
                    break
            elif bk["step"] is None:
                same = (round_distance(d) == bk["round"]) if (bk["round"] is not None and on_round_distance(d)) else abs(d - mean(bk["d"])) / mean(bk["d"]) < SAME_DISTANCE
                if same:
                    b = k
                    break
        if b is None:
            buckets.append({"step": step, "round": round_distance(d) if on_round_distance(d) else None, "d": []})
            b = len(buckets) - 1
        buckets[b]["d"].append(d)
        of[i] = b
    return of


def classify_recoveries(laps, roles, rule="new", tagged=False):
    idx = [i for i in range(len(laps)) if roles[i] == "R" and i > 0 and roles[i - 1] == "E"]
    if not idx:
        return []
    ls = [laps[i] for i in idx]
    if rule == "app":
        measured = measured_app(ls)
    else:
        of = efforts_group(laps, roles, tagged)
        by = {}
        for k, i in enumerate(idx):
            by.setdefault(of[i - 1], []).append(k)
        dec = {g: measured_group([ls[k] for k in ks]) for g, ks in by.items()}
        measured = [dec[of[i - 1]] for i in idx]
    kinds = []
    for mode in (True, False):
        members = sorted([k for k in range(len(idx)) if measured[k] == mode], key=lambda k: val(ls[k], mode))
        cluster = []

        def close():
            nonlocal cluster
            if not cluster:
                return
            values = [val(ls[k], mode) for k in cluster]
            med = median(values)
            label = (f"{nearest50(med)} m" if med < 950 else nominal_distance(med)) if mode else nominal_duration(math.floor((med + 5) / 15) * 15 if med >= 45 else med)
            kinds.append({"label": label, "measured": mode, "first": min(idx[k] for k in cluster), "laps": [idx[k] for k in cluster]})
            cluster = []

        for k in members:
            if cluster and val(ls[k], mode) > val(ls[cluster[-1]], mode) * 1.15:
                close()
            cluster.append(k)
        close()
    kinds.sort(key=lambda k: k["first"])
    for ki, k in enumerate(kinds):
        for i in k["laps"]:
            laps[i].kind = ki
            laps[i - 1].kind = ki
    return kinds


def val(l, measured):
    return l.distance if measured else l.duration


def measured_app(ls):
    dm = sum(1 for l in ls if is_round_measure(l.distance) and not is_round_time(l.duration))
    dt = sum(1 for l in ls if is_round_time(l.duration) and not is_round_measure(l.distance))
    decided = dm + dt
    if decided > 0:
        default = dm > dt
    elif len(ls) >= 2:
        default = cv([l.distance for l in ls]) < 0.05 and cv([l.distance for l in ls]) < cv([l.duration for l in ls]) * 0.7
    else:
        default = False
    forced = decided > 0 and max(dm, dt) / decided >= 0.6
    out = []
    for l in ls:
        m = is_round_measure(l.distance)
        t = is_round_time(l.duration)
        out.append(default if forced or m == t else m)
    return out


def analyse(raw, rule="new"):
    laps = [Lap(a) for a in json.loads(raw)]
    usable = [l for l in laps if l.distance is not None and (l.duration or 0) > 0]
    if len(usable) < 3:
        return None
    roles = structured_roles(usable)
    tagged = roles is not None
    roles = roles or inferred_roles(usable)
    if roles is None:
        return None
    for l, r in zip(usable, roles):
        l.role = r
    kinds = classify_recoveries(usable, roles, rule, tagged)

    buckets = []
    for i, l in enumerate(usable):
        if roles[i] != "E":
            continue
        d = l.distance
        step = l.step if tagged else None
        b = None
        for bk in buckets:
            if step is not None:
                if bk["step"] == step:
                    b = bk
                    break
            elif bk["step"] is None:
                if bk["round"] is not None and on_round_distance(d):
                    same = round_distance(d) == bk["round"]
                else:
                    same = abs(d - mean(bk["d"])) / mean(bk["d"]) < SAME_DISTANCE
                if same:
                    b = bk
                    break
        if b is None:
            b = {"step": step, "round": round_distance(d) if on_round_distance(d) else None, "d": [], "reps": []}
            buckets.append(b)
        b["d"].append(d)
        b["reps"].append(i)

    def build(rep_idx):
        reps = [usable[i] for i in rep_idx]
        durations = [r.duration for r in reps]
        distances = [r.distance for r in reps]
        round_dur = all(abs(x - rnd(x / 15) * 15) <= 1 for x in durations)
        time_based = len(reps) > 1 and round_dur and cv(durations) < cv(distances) and not all(on_round_distance(x) for x in distances)
        label = nominal_duration(mean(durations)) if time_based else nominal_distance(mean(distances))
        recs = [usable[i + 1] for i in rep_idx if i + 1 < len(usable) and roles[i + 1] == "R"]
        rec = None
        if recs:
            labels = []
            for r in recs:
                if r.kind is not None and kinds[r.kind]["label"] not in labels:
                    labels.append(kinds[r.kind]["label"])
            rec = " / ".join(labels) if labels else nominal_duration(nearest_multiple(median([r.duration for r in recs]), 15))
        return {"label": label, "n": len(reps), "time": time_based, "idx": rep_idx, "rec": rec,
                "speed": sum(distances) / sum(durations), "dur": mean(durations), "dist": mean(distances)}

    groups = []
    for g in [build(b["reps"]) for b in buckets]:
        twin = next((k for k, x in enumerate(groups) if x["label"] == g["label"] and x["time"] == g["time"]), -1)
        if twin == -1:
            groups.append(g)
        else:
            groups[twin] = build(sorted(groups[twin]["idx"] + g["idx"]))
    title = " + ".join((f"{g['n']} × " if g["n"] > 1 else "") + g["label"] + (f" (r : {g['rec']})" if g["rec"] else "") for g in groups)
    return {"title": title, "groups": groups, "kinds": kinds, "laps": usable, "roles": roles}


# ---- ground truth, from the Strava name ----
UNIT_TIME = re.compile(r"\d\s*(min|sec|s)\b|\d\s*min|\d\s*sec|\d\s*s\b")
UNIT_DIST = re.compile(r"\d\s*m\b(?!in)|\d\s*km")


def truth_recovery(name):
    """Kinds written after 'r :' : set of 'T' (time) and 'D' (distance), None when absent or unreadable."""
    blocks = re.findall(r"\(\s*[^()]*?\br\s*:\s*([^()]*?)\)", name)
    kinds = set()
    for b in blocks:
        for tok in re.split(r"[+/|]|\s-\s", b):
            tok = tok.strip()
            if re.search(r"\d\s*(min|sec|s)\b", tok) or re.fullmatch(r"\d+\s*min\s*\d*", tok):
                kinds.add("T")
            elif re.search(r"\d\s*m\b", tok):
                kinds.add("D")
    return kinds or None


def truth_kinds_list(name):
    out = []
    for b in re.findall(r"\(\s*[^()]*?\br\s*:\s*([^()]*?)\)", name):
        for tok in re.split(r"[+/|]|\s-\s", b):
            tok = tok.strip()
            if re.search(r"\d\s*(min|sec|s)\b", tok):
                out.append(("T", tok))
            elif re.search(r"\d\s*m\b", tok):
                out.append(("D", tok))
    return out


def app_kinds(a):
    return {("D" if k["measured"] else "T") for k in a["kinds"]} or None


def main():
    db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    verbose = "--verbose" in sys.argv
    rows = db.execute(
        "select a.id,a.start_local,s.name,a.interval_title,d.laps from activities a "
        "join strava_activities s on s.garmin_id=a.id join activity_details d on d.id=a.id "
        "where a.interval_title is not null and d.laps is not null order by a.start_local desc"
    ).fetchall()
    same_title = bad_title = 0
    ok = ko = 0
    for id_, start, name, title, laps in rows:
        a = analyse(laps, 'app' if '--ancienne' in sys.argv else 'new')
        if a is None:
            continue
        if a["title"] == title.replace("\u00a0", " "):
            same_title += 1
        else:
            bad_title += 1
        t = truth_recovery(name)
        k = app_kinds(a)
        if t is None:
            continue
        if t == k:
            ok += 1
        else:
            ko += 1
            if verbose:
                recs = [(round(a["laps"][i].distance), round(a["laps"][i].duration)) for i in range(len(a["laps"])) if a["roles"][i] == "R" and i > 0 and a["roles"][i - 1] == "E"]
                print(f"{start[:10]} {name}\n     app : {a['title']}\n     vrai: {sorted(t)} app: {sorted(k or [])}  récups (m,s): {recs}")
    print(f"(titres identiques à ceux de l'app avant la règle : {same_title}/{same_title + bad_title}, ils changent avec la nouvelle règle)")
    print(f"types de récup (temps/distance) : {ok} justes, {ko} faux")


if __name__ == "__main__":
    main()
