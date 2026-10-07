#!/usr/bin/env python3
"""Lists the hitches in the current game session, from the HitchTrace lines in the game log.

Covers three kinds of lag:
  - freezes: frames that took long to draw, with a best guess at why
  - integrated-server lag: slow server ticks, which delay breaking and placing blocks, item use and mobs even when
    every frame is quick
  - input lag with smooth frames: key and mouse events read late, or frames that took long to reach the screen after
    the input in them (frames queued behind a busy GPU)

Also lists key presses and releases, mouse movement and window focus changes, for keys that seem to stay held or a
camera that keeps turning: whether the release or the movement reached the game late, never, or on time.

Needs the game launched with -Dciderlight.debug=true (and -Dciderlight.hitchTraceSeconds=3600 to trace past the first
30 seconds of a world). Times are wall-clock, so they can be matched against what you saw.

  tools/hitch-report.py                 the last 2 minutes
  tools/hitch-report.py --last 600      the last 10 minutes
  tools/hitch-report.py --at 18:39:29   10 seconds either side of a moment
  tools/hitch-report.py --system        also show trackpad gestures and focus changes from the macOS log
"""
import argparse
import datetime
import os
import re
import subprocess
import sys

DEFAULT_LOG = os.path.expanduser("~/Library/Application Support/minecraft/logs/latest.log")
HEADER = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\] ")
SECOND = re.compile(r"^\+([0-9.]+)s: (\d+) frames .*?max ([0-9.]+) ms\), (\d+) reached the screen, (\d+) not presented")
INPUT = re.compile(r"\| input: (\d+) events, read max ([0-9.]+)ms after they happened, to screen max ([0-9.]+)ms \((\d+) of (\d+) frames over 60ms\)")
KEY = re.compile(r"key (\S+) (down|up)(?: after (\d+)ms held| \(no press seen\))?, read (\d+)ms late")
FOCUS = re.compile(r"window focus (gained|lost)")
MOUSE = re.compile(r"\| mouse moved (\d+) x (\d+) px in (\d+) events")
HELD = re.compile(r"\| keys held: ([^|\n]+)")
READ = re.compile(r"\| frame read input to screen avg ([0-9.]+) max ([0-9.]+)ms")
SERVER = re.compile(r"\| server: (\d+) ticks, max ([0-9.]+)ms, (\d+) over 50ms")
FRAME = re.compile(r"^\s+\+([0-9.]+)s frame ([0-9.]+)ms \(render thread on cpu ([0-9.]+)\)( NOT PRESENTED)?:(.*)")


def clock(seconds):
    seconds %= 86400
    return "%02d:%02d:%06.3f" % (seconds // 3600, seconds % 3600 // 60, seconds % 60)


def parse(path):
    """Splits the log into worlds (the trace restarts at 0 in each) and works out each one's wall-clock origin."""
    worlds = []
    world = None
    header = None
    with open(path, errors="replace") as f:
        for line in f:
            m = HEADER.match(line)
            if m:
                header = int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3])
                continue
            if "WORLD JOINED, tracing" in line:
                world = {"seconds": [], "frames": [], "lo": -1e18, "hi": 1e18}
                worlds.append(world)
                continue
            if world is None:
                continue
            m = SECOND.match(line)
            if m and header is not None:
                t = float(m[1])
                # The per-second line is logged during the second named by the header before it.
                world["lo"] = max(world["lo"], header - t)
                world["hi"] = min(world["hi"], header + 1 - t)
                second = {"t": t, "frames": int(m[2]), "max": float(m[3]), "shown": int(m[4]),
                          "not_presented": int(m[5]), "line": line.strip()}
                i = INPUT.search(line)
                if i:
                    second.update(inputs=int(i[1]), read=float(i[2]), to_screen=float(i[3]), late=int(i[4]),
                                  measured=int(i[5]))
                mo = MOUSE.search(line)
                if mo:
                    second.update(mouse_x=int(mo[1]), mouse_y=int(mo[2]), mouse_events=int(mo[3]))
                kh = HELD.search(line)
                if kh:
                    second["held"] = kh[1].strip()
                r = READ.search(line)
                if r:
                    second.update(read_avg=float(r[1]), read_max=float(r[2]))
                v = SERVER.search(line)
                if v:
                    second.update(ticks=int(v[1]), tick_max=float(v[2]), slow_ticks=int(v[3]))
                world["seconds"].append(second)
                continue
            m = FRAME.match(line)
            if m:
                world["frames"].append({"t": float(m[1]), "ms": float(m[2]), "cpu": float(m[3]),
                                        "not_presented": bool(m[4]), "detail": m[5].strip()})
    for w in worlds:
        w["origin"] = w["lo"] if w["lo"] > -1e17 else None
    return [w for w in worlds if w["origin"] is not None]


def field(detail, name):
    m = re.search(re.escape(name) + r" ([0-9.]+)", detail)
    return float(m[1]) if m else 0.0


def visible_at(world, t):
    """Whether the window was on screen around time t: frames reached the screen in that second and the ones beside it."""
    near = [s for s in world["seconds"] if t - 1.5 <= s["t"] <= t + 1.5]
    return bool(near) and all(s["shown"] > 0 for s in near)


def cause(world, f):
    """A best guess at what held the frame up, from the trace columns."""
    d = f["detail"]
    reasons = []
    wall = world["origin"] + f["t"]
    start = wall - f["ms"] / 1000.0
    if (start % 60) > 59.7 or (wall % 60) < 0.3:
        reasons.append("ON THE MINUTE (macOS, likely the menu-bar clock)")
    if f["not_presented"]:
        reasons.append("macOS held the screen (app switch / gesture?)")
    gc = re.search(r"gc ([0-9]+)ms", d)
    if gc and int(gc[1]) >= 10:
        reasons.append("garbage collection %sms (with ZGC this is mostly concurrent, not a pause)" % gc[1])
    waits = {
        "waiting for macOS to supply the next screen image": field(d, "drawable prefetch") + field(d, "drawable acquire"),
        "waiting for the GPU": field(d, "gpu wait (submit)") + field(d, "gpu wait (fence)"),
        "client ticks": field(d, "client ticks"),
        "section selection": field(d, "section selection"),
        "shader/pipeline compiles": field(d, "shader compile") + field(d, "pipeline compile"),
    }
    for name, ms in sorted(waits.items(), key=lambda kv: -kv[1]):
        if ms >= max(8.0, f["ms"] * 0.25):
            reasons.append("%s %.0fms" % (name, ms))
    acquire = re.search(r"acquire@([0-9.]+)", d)
    accounted = sum(waits.values())
    if acquire and float(acquire[1]) > 30 and f["cpu"] < float(acquire[1]) * 0.5 and accounted < float(acquire[1]) * 0.5:
        reasons.append("render thread idle %.0fms before the frame began (blocked outside what the trace measures)"
                       % (float(acquire[1]) - f["cpu"]))
    gpu = re.search(r"gpu max ([0-9.]+)", d)
    if gpu and float(gpu[1]) >= 25:
        reasons.append("heavy GPU frame %.0fms" % float(gpu[1]))
    return "; ".join(reasons) or "no single cause in the trace"


def system_events(start, end):
    """Trackpad gestures, focus changes and window visibility from the macOS unified log."""
    day = datetime.date.today().isoformat()
    cmd = ["/usr/bin/log", "show", "--style", "compact", "--start", "%s %s" % (day, clock(start)[:8]),
           "--end", "%s %s" % (day, clock(end + 1)[:8]),
           "--predicate", 'process == "WindowServer" OR (process == "gamepolicyd" AND eventMessage CONTAINS "java")']
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=120).stdout
    except Exception as e:
        return ["  (macOS log unavailable: %s)" % e]
    events = []
    for line in out.splitlines():
        t = line[11:23]
        if re.search(r"digitizer event with ([3-9]) children", line):
            n = re.search(r"with (\d) children", line)[1]
            events.append("%s  %s-finger trackpad touch" % (t, n))
        elif "SystemGestures] Posting gesture update" in line:
            if not events or not events[-1].endswith("system gesture (Spaces / Mission Control)"):
                events.append("%s  system gesture (Spaces / Mission Control)" % t)
        elif "the front process" in line:
            app = re.search(r"Deferring events from frontmost process PSN \S+ \((\w+)\)", line)
            events.append("%s  front app changed%s" % (t, " to " + app[1] if app else ""))
        elif "Deferring events from frontmost process" in line:
            app = re.search(r"\((\w[\w ]*)\)", line)
            if app and (not events or "front app" not in events[-1]):
                events.append("%s  front app is %s" % (t, app[1]))
        elif "java" in line and "running-NotVisible" in line and "anon<java>" in line:
            events.append("%s  game window not visible" % t)
    return ["  " + e for e in events] or ["  (none)"]


def report_server(world, seconds):
    """Seconds with a server tick over 50 ms, the whole budget of a tick: the server fell behind there."""
    measured = [s for s in seconds if "ticks" in s]
    print("\nIntegrated server:")
    if not measured:
        print("  not measured (this log is from a build without server tick timing)")
        return
    slow = [s for s in measured if s["tick_max"] >= 50]
    worst = max(s["tick_max"] for s in measured)
    if not slow:
        print("  no lag: every tick under 50ms (worst %.1fms)" % worst)
        return
    for s in slow:
        print("  %s  %d ticks, slowest %.0fms, %d over 50ms%s" % (
            clock(world["origin"] + s["t"])[:8], s["ticks"], s["tick_max"], s["slow_ticks"],
            "  (actions like breaking blocks lag here)" if s["shown"] > 0 else ""))
    for f in world["frames"]:
        wall = world["origin"] + f["t"]
        for m in re.finditer(r"server tick ([0-9]+)ms", f["detail"]):
            if any(abs(world["origin"] + s["t"] - wall) < 1.5 for s in slow):
                print("    %s  one tick took %sms" % (clock(wall), m[1]))


def report_input(world, seconds, lo, hi):
    """Input that reached the screen late although frames were quick."""
    timed = [s for s in seconds if s.get("read_avg", 0) > 0 and s["shown"] > 0]
    if timed:
        typical = sorted(s["read_avg"] for s in timed)[len(timed) // 2]
        print("\nFrame latency (from a frame reading input to it reaching the screen; the least lag any input can have):")
        print("  typical %.0fms" % typical)
        slow = [s for s in timed if s["read_avg"] >= max(45.0, 1.6 * typical)]
        for s in slow:
            print("  %s  avg %.0fms, worst %.0fms, %d fps%s" % (
                clock(world["origin"] + s["t"])[:8], s["read_avg"], s["read_max"], s["frames"],
                "  << frames were smooth (max %.0fms): lag without a freeze" % s["max"] if s["max"] < 35 else ""))
    measured = [s for s in seconds if s.get("inputs", 0) > 0 and s["shown"] > 0]
    print("\nInput lag (key/mouse to screen):")
    if not measured:
        print("  not measured (no input while on screen, or a build without input timing)")
        return
    with_input = [s for s in measured if s["measured"] > 0]
    if with_input:
        typical = sorted(s["to_screen"] for s in with_input)[len(with_input) // 2]
        print("  typical worst-per-second: %.0fms from input to screen" % typical)
    late = [s for s in measured if s["late"] > 0 or s["read"] >= 50]
    if not late:
        print("  no lag: input always on screen within 60ms and read within 50ms")
        return
    for s in late:
        smooth = s["max"] < 35
        print("  %s  to screen up to %.0fms (%d of %d frames over 60ms), read up to %.0fms after it happened%s" % (
            clock(world["origin"] + s["t"])[:8], s["to_screen"], s["late"], s["measured"], s["read"],
            "  << frames were smooth (max %.0fms): lag without a freeze" % s["max"] if smooth else ""))
    for f in world["frames"]:
        wall = world["origin"] + f["t"]
        m = re.search(r"input read ([0-9]+)ms after it happened", f["detail"])
        if m and lo <= wall <= hi and visible_at(world, f["t"]):
            print("    %s  input read %sms late (frame %.0fms)" % (clock(wall), m[1], f["ms"]))


def report_keys(world, seconds, lo, hi, everything):
    """Key presses and releases, focus changes and (around a moment) mouse movement."""
    events = []
    for f in world["frames"]:
        wall = world["origin"] + f["t"]
        if not lo <= wall <= hi:
            continue
        for m in KEY.finditer(f["detail"]):
            events.append((wall, "key", m))
        for m in FOCUS.finditer(f["detail"]):
            events.append((wall, "focus", m))
    print("\nKeys and focus:")
    shown = 0
    for wall, kind, m in events:
        if kind == "focus":
            print("  %s  window focus %s%s" % (clock(wall), m[1], "  << keys released now never reach the game" if m[1] == "lost" else ""))
            shown += 1
            continue
        name, what, held, late = m[1], m[2], m[3], int(m[4])
        flags = []
        if late >= 50:
            flags.append("reached the game %dms late" % late)
        if what == "up" and held is None:
            flags.append("release without a press seen")
        if everything or flags:
            print("  %s  %s %s%s, read %dms late%s" % (clock(wall), name, what, " after %sms" % held if held else "", late,
                                                      "  << " + "; ".join(flags) if flags else ""))
            shown += 1
    if not shown:
        print("  nothing unusual: every press and release reached the game within 50ms" if events else "  no key events in this window")
    if everything:
        print("\nMouse and held keys, second by second:")
        for s in seconds:
            if "mouse_events" in s:
                print("  %s  mouse %4d x %4d px in %3d events%s" % (clock(world["origin"] + s["t"])[:8], s["mouse_x"], s["mouse_y"],
                                                                   s["mouse_events"], "  | held: " + s["held"] if s.get("held") else ""))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--log", default=DEFAULT_LOG, help="game log (default: the launcher's latest.log)")
    ap.add_argument("--last", type=float, default=120, help="seconds back from the end of the log (default 120)")
    ap.add_argument("--at", help="HH:MM:SS to look around instead (10 seconds either side)")
    ap.add_argument("--window", type=float, default=10, help="seconds either side of --at (default 10)")
    ap.add_argument("--threshold", type=float, default=35, help="frames at least this long, in ms (default 35)")
    ap.add_argument("--all", action="store_true", help="also list frames while the window was hidden or switching")
    ap.add_argument("--system", action="store_true", help="add trackpad gestures and focus changes from the macOS log")
    args = ap.parse_args()

    if not os.path.exists(args.log):
        sys.exit("No log at %s" % args.log)
    worlds = parse(args.log)
    if not worlds:
        sys.exit("No HitchTrace in %s. Launch with -Dciderlight.debug=true and -Dciderlight.hitchTraceSeconds=3600." % args.log)
    world = worlds[-1]
    if args.at:
        h, m, s = (int(x) for x in args.at.split(":"))
        at = h * 3600 + m * 60 + s
        # The world (a log can hold several) that was being played at that time.
        world = ([w for w in worlds if w["origin"] <= at] or worlds[:1])[-1]
        lo, hi = at - args.window, at + args.window + 1
    origin = world["origin"]
    end = origin + max([s["t"] for s in world["seconds"]] + [f["t"] for f in world["frames"]])
    if not args.at:
        lo, hi = end - args.last, end + 1

    print("World traced from %s (log %s)" % (clock(origin)[:8], args.log))
    print("Looking at %s to %s, frames of %.0fms or more%s\n"
          % (clock(lo)[:8], clock(hi)[:8], args.threshold, "" if args.all else " while the game was on screen"))

    seconds = [s for s in world["seconds"] if lo <= origin + s["t"] <= hi]
    played = [s for s in seconds if s["shown"] > 0]
    if seconds:
        fps = [s["frames"] for s in played] or [0]
        print("%d s traced, %d s on screen, %d-%d fps on screen" % (len(seconds), len(played), min(fps), max(fps)))

    hitches = []
    for f in world["frames"]:
        wall = origin + f["t"]
        if not (lo <= wall <= hi) or f["ms"] < args.threshold:
            continue
        on_screen = visible_at(world, f["t"])
        if not on_screen and not args.all:
            continue
        hitches.append((wall, f, on_screen))

    # Runs of near-identical ~33ms frames are the game's own 30 fps limit (paused or inactive), not hitches.
    capped = [h for h in hitches if 30 <= h[1]["ms"] <= 42 and "drawable" not in h[1]["detail"]]
    if len(capped) > 20:
        hitches = [h for h in hitches if h not in capped]
        print("(%d frames at the 30 fps limit for a paused or inactive game left out)" % len(capped))

    print()
    if not hitches:
        print("No frames of %.0fms or more%s." % (args.threshold, "" if args.all else " while on screen"))
    for wall, f, on_screen in hitches:
        flags = "" if on_screen else "  [window hidden or switching]"
        print("%s  %4.0fms%s" % (clock(wall), f["ms"], flags))
        print("      " + cause(world, f))
        print("      trace: " + f["detail"][:220])

    report_server(world, seconds)
    report_input(world, seconds, lo, hi)
    report_keys(world, seconds, lo, hi, args.at is not None)

    if args.system:
        print("\nmacOS events:")
        for e in system_events(lo, hi):
            print(e)


if __name__ == "__main__":
    main()
