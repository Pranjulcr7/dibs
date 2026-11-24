# SPDX-License-Identifier: Apache-2.0
"""Black-box UI driver for the Dibs end-to-end checks.

Talks to a device or emulator over adb only: it reads the screen with
`uiautomator dump` and acts with `input`. It knows nothing about the app's
internals, so what passes here passes for a person holding the phone.

Standard library only; needs `adb` on PATH or ANDROID_HOME set.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass

PACKAGE = "app.dibs"
ACTIVITY = "app.dibs/.MainActivity"


def _adb_path() -> str:
    found = shutil.which("adb")
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
    return os.path.join(home, "platform-tools", "adb")


ADB = _adb_path()


class StepFailed(AssertionError):
    """A flow step did not see what it expected; the message says what was on screen."""


@dataclass
class Node:
    text: str
    desc: str
    cls: str
    bounds: tuple[int, int, int, int]
    clickable: bool
    checked: bool
    enabled: bool
    focused: bool

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2

    @property
    def label(self) -> str:
        return self.text or self.desc


class Device:
    def __init__(self, serial: str, name: str = ""):
        self.serial = serial
        self.name = name or serial

    # -- adb ------------------------------------------------------------------

    def adb(self, *args: str, check: bool = True, binary: bool = False, timeout: int = 120):
        result = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.decode(errors='replace')}")
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def shell(self, command: str, check: bool = True) -> str:
        return self.adb("shell", command, check=check)

    def wait_for_boot(self, timeout: int = 420) -> None:
        self.adb("wait-for-device", timeout=timeout)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.shell("getprop sys.boot_completed", check=False).strip() == "1":
                # Keep the screen on and animations off: faster and far less flaky.
                for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                    self.shell(f"settings put global {setting} 0", check=False)
                self.shell("svc power stayon true", check=False)
                self.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard", check=False)
                return
            time.sleep(2)
        raise RuntimeError(f"{self.name}: did not finish booting in {timeout}s")

    # -- app lifecycle ----------------------------------------------------------

    def install(self, apk: str) -> None:
        self.adb("install", "-r", "-g", apk, timeout=300)

    def clear_data(self) -> None:
        self.shell(f"pm clear {PACKAGE}")

    def launch(self) -> None:
        self.shell(f"am start -W -n {ACTIVITY}")

    def force_stop(self) -> None:
        self.shell(f"am force-stop {PACKAGE}")

    def kill_process(self) -> None:
        """Process death as the OS does it: the task survives, the process does not."""
        self.shell("input keyevent KEYCODE_HOME")
        time.sleep(1)
        self.shell(f"am kill {PACKAGE}")

    def open_link(self, link: str) -> None:
        self.shell(f"am start -W -a android.intent.action.VIEW -d '{link}' {PACKAGE}")

    def open_file(self, provider_path: str) -> None:
        """Opens a file staged in the app's own share directory, as 'Open with Dibs' would."""
        uri = f"content://{PACKAGE}.fileprovider/sync/{provider_path}"
        # No grant flag: the app is reading its own provider, as it would a file a chat app handed it.
        self.shell(
            f"am start -W -a android.intent.action.VIEW -d '{uri}' -t application/octet-stream -n {ACTIVITY}"
        )

    def pull_staged(self, name: str) -> bytes:
        """Reads a file the app staged for the share sheet (debuggable builds only)."""
        return self.adb("exec-out", "run-as", PACKAGE, "cat", f"cache/sync/{name}", binary=True)

    def push_staged(self, name: str, data: bytes) -> None:
        """Places a file where a chat app would have delivered it, readable by the app."""
        local = f"/tmp/dibs-e2e-{os.getpid()}-{name}"
        with open(local, "wb") as handle:
            handle.write(data)
        try:
            self.adb("push", local, f"/data/local/tmp/{name}")
            self.shell(f"run-as {PACKAGE} mkdir -p cache/sync")
            self.shell(f"run-as {PACKAGE} cp /data/local/tmp/{name} cache/sync/{name}")
        finally:
            os.remove(local)

    def app_file_list(self, directory: str) -> list[str]:
        return self.shell(f"run-as {PACKAGE} ls {directory}", check=False).split()

    # -- reading the screen -------------------------------------------------------

    def nodes(self) -> list[Node]:
        raw = ""
        for _ in range(4):
            self.shell("uiautomator dump /sdcard/dibs-ui.xml >/dev/null 2>&1", check=False)
            raw = self.shell("cat /sdcard/dibs-ui.xml", check=False)
            if raw.startswith("<?xml"):
                break
            time.sleep(0.5)
        if not raw.startswith("<?xml"):
            return []
        result = []
        for element in ET.fromstring(raw).iter("node"):
            match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", element.get("bounds", ""))
            if not match:
                continue
            result.append(
                Node(
                    text=element.get("text", ""),
                    desc=element.get("content-desc", ""),
                    cls=element.get("class", ""),
                    bounds=tuple(int(v) for v in match.groups()),
                    clickable=element.get("clickable") == "true",
                    checked=element.get("checked") == "true",
                    enabled=element.get("enabled") == "true",
                    focused=element.get("focused") == "true",
                )
            )
        return result

    def labels(self) -> list[str]:
        return [node.label for node in self.nodes() if node.label]

    def _match(self, nodes: list[Node], wanted: str, exact: bool) -> list[Node]:
        if exact:
            return [n for n in nodes if n.text == wanted or n.desc == wanted]
        return [n for n in nodes if wanted in n.text or wanted in n.desc]

    def find(self, wanted: str, timeout: float = 12, exact: bool = False, index: int = 0) -> Node:
        deadline = time.time() + timeout
        seen: list[str] = []
        while True:
            nodes = self.nodes()
            matches = self._match(nodes, wanted, exact)
            if len(matches) > index:
                return matches[index]
            seen = [n.label for n in nodes if n.label]
            if time.time() > deadline:
                raise StepFailed(f"[{self.name}] expected '{wanted}' on screen; saw: {seen}")
            time.sleep(0.4)

    def has(self, wanted: str, exact: bool = False) -> bool:
        return bool(self._match(self.nodes(), wanted, exact))

    def expect(self, *wanted: str, timeout: float = 12) -> None:
        for item in wanted:
            self.find(item, timeout=timeout)

    def expect_gone(self, wanted: str, timeout: float = 8) -> None:
        deadline = time.time() + timeout
        while self.has(wanted):
            if time.time() > deadline:
                raise StepFailed(f"[{self.name}] expected '{wanted}' to disappear; saw: {self.labels()}")
            time.sleep(0.4)

    # -- acting -------------------------------------------------------------------

    def tap(self, wanted: str, timeout: float = 12, exact: bool = False, index: int = 0) -> None:
        x, y = self.find(wanted, timeout=timeout, exact=exact, index=index).center
        self.shell(f"input tap {x} {y}")
        time.sleep(0.5)

    def tap_last(self, wanted: str, exact: bool = True) -> None:
        """Taps the last (lowest on screen) match — for repeated chips in a list."""
        self.find(wanted, exact=exact)
        matches = self._match(self.nodes(), wanted, exact)
        x, y = matches[-1].center
        self.shell(f"input tap {x} {y}")
        time.sleep(0.5)

    def tap_below(self, anchor: str, wanted: str) -> None:
        """Taps the first `wanted` that sits below `anchor` — a chip belonging to a list row."""
        deadline = time.time() + 12
        while True:
            nodes = self.nodes()
            anchors = self._match(nodes, anchor, exact=True)
            if anchors:
                floor = anchors[0].center[1]
                below = sorted(
                    (n for n in self._match(nodes, wanted, exact=True) if n.center[1] > floor),
                    key=lambda n: n.center[1],
                )
                if below:
                    x, y = below[0].center
                    self.shell(f"input tap {x} {y}")
                    time.sleep(0.5)
                    return
            if time.time() > deadline:
                raise StepFailed(
                    f"[{self.name}] expected '{wanted}' below '{anchor}'; saw: {[n.label for n in nodes if n.label]}"
                )
            time.sleep(0.4)

    def tap_if_present(self, wanted: str) -> bool:
        if self.has(wanted):
            self.tap(wanted)
            return True
        return False

    def type(self, text: str) -> None:
        """Types into the focused field. `input text` needs spaces as %s and shell quoting."""
        escaped = text.replace("\\", "\\\\").replace("'", "'\\''").replace(" ", "%s")
        for special in "&|;<>()$`\"":
            escaped = escaped.replace(special, "\\" + special)
        self.shell(f"input text '{escaped}'")
        time.sleep(0.4)

    def fill(self, field_label: str, text: str) -> None:
        self.tap(field_label)
        self.type(text)

    def back(self) -> None:
        self.shell("input keyevent KEYCODE_BACK")
        time.sleep(0.5)

    def hide_keyboard(self) -> None:
        if "mInputShown=true" in self.shell("dumpsys input_method | grep mInputShown", check=False):
            self.back()

    def scroll_down(self) -> None:
        self.shell("input swipe 540 1700 540 700 250")
        time.sleep(0.5)

    def rotate(self, landscape: bool) -> None:
        self.shell("settings put system accelerometer_rotation 0")
        self.shell(f"settings put system user_rotation {1 if landscape else 0}")
        time.sleep(1.5)

    def set_font_scale(self, scale: float) -> None:
        self.shell(f"settings put system font_scale {scale}")
        time.sleep(1.0)

    def set_dark(self, dark: bool) -> None:
        self.shell(f"cmd uimode night {'yes' if dark else 'no'}")
        time.sleep(1.0)

    def airplane(self, on: bool) -> None:
        self.shell(f"cmd connectivity airplane-mode {'enable' if on else 'disable'}")

    def screenshot(self, path: str) -> None:
        data = self.adb("exec-out", "screencap", "-p", binary=True)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as handle:
            handle.write(data)
