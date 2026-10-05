#!/usr/bin/env python3
"""Compile all shader variants and run numerical regressions on an Apple Metal GPU."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="honeycrisp-shader-checks-") as tmp:
    binary = str(Path(tmp) / "check-shaders")
    subprocess.run(["clang", "-fobjc-arc", "-O2", "-framework", "Metal",
                    "-framework", "Foundation", str(root / "tests/check_shaders.m"), "-o", binary], check=True)
    subprocess.run([binary], cwd=root, check=True)
