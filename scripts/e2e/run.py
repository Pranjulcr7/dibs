#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""End-to-end checks of the installed Dibs app, driven like a person would.

    python3 scripts/e2e/run.py                      # every flow, two emulators
    python3 scripts/e2e/run.py F04 F22              # only these flows
    python3 scripts/e2e/run.py --apk path/to.apk    # install first

Needs two running devices (default emulator-5554 and emulator-5556) with a
*debug* build installed: the two-phone flows move files between the phones
with `run-as`, standing in for the chat app a real invite travels through.
Exit code is the number of failed flows. Flow ids match ROADMAP.md §4 and
docs/requirements.yaml.
"""
from __future__ import annotations

import argparse
import os
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(__file__))
from ui import Device, StepFailed  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "build", "e2e")
FLOWS: dict[str, tuple[str, callable]] = {}


def flow(flow_id: str, title: str):
    def register(function):
        FLOWS[flow_id] = (title, function)
        return function
    return register


# -- shared steps ----------------------------------------------------------------

def fresh(device: Device, name: str | None) -> None:
    """A brand-new install: wiped data, onboarding walked (or skipped when name is None)."""
    device.force_stop()
    device.clear_data()
    device.launch()
    device.expect("Split expenses with friends")
    if name is None:
        device.tap("Skip")
    else:
        device.tap("Next")
        device.expect("Your data stays on this phone")
        device.tap("Next")
        device.expect("What should we call you?")
        type_into(device, "Your name", name)
        device.hide_keyboard()
        device.tap("Get started")
    device.expect("No groups yet", "Create a group", "Import from Splitwise", "Join a group")


def _focused_text(device: Device) -> str | None:
    for node in device.nodes():
        if node.focused and "EditText" in node.cls:
            return node.text
    return None


def type_into(device: Device, label: str, text: str) -> None:
    """Focuses a field, appends `text`, and proves the field now ends with exactly that.

    Emulators under load drop or delay key events, and on a cold start the
    keyboard can take seconds to attach. So: wait for real focus, type, read
    the field back, and if characters went missing, delete the partial input
    and try again rather than typing on top of it.
    """
    for _ in range(4):
        # Exact match: "Your name" must not hit "Your name appears next to expenses…".
        device.tap(label, exact=True)
        deadline = time.time() + 6
        before = _focused_text(device)
        while before is None and time.time() < deadline:
            time.sleep(0.4)
            before = _focused_text(device)
        if before is None:
            continue
        # Tapping a field puts the cursor where the finger landed; typing must append.
        device.shell("input keyevent KEYCODE_MOVE_END")
        device.type(text)
        time.sleep(0.6)
        after = _focused_text(device)
        if after == before + text:
            return
        if after is not None and after.startswith(before) and len(after) > len(before):
            device.shell("input keyevent KEYCODE_MOVE_END")
            device.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * (len(after) - len(before))))
        time.sleep(1)
    raise StepFailed(f"[{device.name}] could not type '{text}' into '{label}'; saw: {device.labels()}")


def replace_in(device: Device, label: str, text: str) -> None:
    """Replaces whatever a field holds with `text`."""
    device.tap(label, exact=True)
    deadline = time.time() + 6
    current = _focused_text(device)
    while current is None and time.time() < deadline:
        time.sleep(0.4)
        current = _focused_text(device)
    if current is None:
        raise StepFailed(f"[{device.name}] '{label}' never took focus; saw: {device.labels()}")
    if current:
        device.shell("input keyevent KEYCODE_MOVE_END")
        device.shell("input keyevent " + " ".join(["KEYCODE_DEL"] * len(current)))
    device.type(text)
    time.sleep(0.6)
    if _focused_text(device) != text:
        raise StepFailed(f"[{device.name}] '{label}' holds {_focused_text(device)!r}, wanted {text!r}")


def create_group(device: Device, name: str, emoji: str | None = "✈️") -> None:
    """From the groups list: the empty state and the end of a non-empty list both offer it."""
    if not device.has("Create a group"):
        device.scroll_down()
    device.tap("Create a group")
    device.expect("New group")
    type_into(device, "Group name", name)
    device.hide_keyboard()
    if emoji:
        device.tap(emoji)
    device.tap("Create", exact=True)
    device.expect(name, "No expenses yet")


def add_members(device: Device, *names: str) -> None:
    device.tap("Members", exact=True)
    device.expect("Add member by name")
    for name in names:
        type_into(device, "Add member by name", name)
        device.tap("Add", exact=True)
        device.expect(name)
    device.hide_keyboard()
    device.tap("Back")


def add_expense(device: Device, amount: str, description: str) -> None:
    """The NFR-2 path: FAB, amount (already focused), description, save."""
    device.tap("Add expense")
    device.expect("New expense")
    device.type(amount)
    type_into(device, "What was it for?", description)
    device.hide_keyboard()
    device.tap("Save expense")
    device.expect(description)


# -- single-phone flows ------------------------------------------------------------

@flow("F01", "First run: three onboarding pages, name, no permission asked")
def f01(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    # Nothing was requested up front (SPEC 4.1 screen 11).
    granted = a.shell("dumpsys package app.dibs | grep 'granted=true' | grep -c 'CAMERA\\|POST_NOTIFICATIONS'", check=False)
    # The driver installs with -g for later flows, so assert on the UI instead: no system dialog appeared.
    assert not a.has("Allow"), "a permission dialog appeared during onboarding"
    a.tap("Settings", exact=True)
    a.expect("Alice")
    a.tap("Groups", exact=True)
    _ = granted


@flow("F01b", "First run skipped: the app is usable with no name")
def f01b(a: Device, b: Device) -> None:
    fresh(a, None)
    a.expect("Create a group")


@flow("F02", "Create a group; the creator is its first member")
def f02(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Goa trip")
    a.expect("✈️ Goa trip", "Settled up", "Expenses", "Balances", "Settle up")
    a.tap("Members", exact=True)
    a.expect("Alice", "You")
    assert a.has("#"), f"creator should carry a fingerprint: {a.labels()}"
    a.tap("Back")
    a.tap("Back")
    a.expect("Goa trip", "1 member")


@flow("F03", "Members: add, rename, deactivate, reactivate; duplicate names flagged")
def f03(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Flat")
    add_members(a, "Bob", "Sam", "Sam")
    a.tap("Members", exact=True)
    a.expect("Same name as another member")
    a.tap("Actions for Bob")
    a.tap("Rename")
    a.expect("Rename member")
    type_into(a, "Name", "by")
    a.tap("Save", exact=True)
    a.expect("Bobby")
    a.tap("Actions for Bobby")
    a.tap("Deactivate")
    a.expect("Deactivated", "Deactivated members are hidden from new expenses")
    a.tap("Actions for Bobby")
    a.tap("Reactivate")
    a.expect_gone("Deactivated members are hidden")
    # No history yet, so removal is offered — and it is refused later once there is history (F25).
    a.tap("Actions for Bobby")
    a.expect("Remove from group")
    a.back()


@flow("F04", "Add an equal-split expense: amount is focused, three taps, balance moves")
def f04(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Dinner club")
    add_members(a, "Bob")
    started = time.time()
    add_expense(a, "30", "Pizza")
    elapsed = time.time() - started
    a.expect("Pizza", "$30.00", "You're owed $15.00")
    print(f"      add-expense wall time through adb: {elapsed:.1f}s (automation overhead included)")


@flow("F05", "Other split modes: live remaining bar, blocked save, one-cent note")
def f05(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Modes")
    add_members(a, "Bob", "Cara")
    # Equal three ways with an indivisible cent (edge 1).
    a.tap("Add expense")
    a.type("10")
    a.expect("to make it add up")
    type_into(a, "What was it for?", "Thirds")
    a.hide_keyboard()
    a.tap("Save expense")
    a.expect("Thirds", "$10.00")
    # Exact amounts: save stays blocked until the amounts sum to the total (edge 9).
    a.tap("Add expense")
    a.type("9")
    a.tap("Amounts")
    a.expect("left to assign")
    type_into(a, "What was it for?", "Exact")
    a.hide_keyboard()
    a.tap("Save expense")
    a.expect("New expense")  # still in the editor: the save was refused
    a.back()
    a.tap_if_present("Discard")


@flow("F06", "Several payers: payments must add up to the total before saving")
def f06(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Airbnb")
    add_members(a, "Bob")
    a.tap("Add expense")
    a.type("90")
    type_into(a, "What was it for?", "Two nights")
    a.hide_keyboard()
    a.tap("Several people")
    # Starts from what was known: the single payer paid it all.
    a.expect("Alice paid", "Bob paid", "Payments add up")
    type_into(a, "Bob paid", "40")
    a.hide_keyboard()
    a.expect("Payments are $40.00 over the total")
    replace_in(a, "Alice paid", "50")
    a.hide_keyboard()
    a.expect("Payments add up")
    a.tap("Save expense")
    # Each owes 45: Alice paid 50 so is owed 5; Bob paid 40 so owes 5.
    a.expect("Two nights", "$90.00", "You're owed $5.00")
    # Reopening shows both payers, and saving a text-only edit leaves the money alone.
    a.tap("Two nights")
    a.expect("Edit expense", "Payments add up")
    type_into(a, "Two nights", " in Goa")
    a.hide_keyboard()
    a.tap("Save expense")
    a.expect("Two nights in Goa", "You're owed $5.00")


@flow("F07", "Edit an expense and read its history")
def f07(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "History")
    add_members(a, "Bob")
    add_expense(a, "20", "Taxi")
    a.tap("Taxi")
    a.expect("Edit expense")
    type_into(a, "Taxi", " home")
    a.hide_keyboard()
    a.tap("Save expense")
    a.expect("Taxi home")
    a.tap("Taxi home")
    a.tap("Edit history")
    a.expect("Created", "Edited")
    a.tap("Close")
    a.back()


@flow("F08", "Delete an expense, find it in trash, restore it")
def f08(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Trash test")
    add_members(a, "Bob")
    add_expense(a, "12", "Coffee")
    a.tap("Coffee")
    a.expect("Edit expense")
    a.tap("Delete expense")
    # SPEC 4.2: a reversible delete offers Undo right away.
    a.expect("Expense moved to trash")
    a.tap("Undo")
    a.expect("Coffee", "You're owed $6.00")
    a.tap("Coffee")
    a.expect("Edit expense")
    a.tap("Delete expense")
    a.expect("No expenses yet", "Settled up")
    a.tap("More actions")
    a.tap("Trash")
    a.expect("Coffee", "days left")
    a.tap("Restore")
    a.expect("Trash is empty")
    a.tap("Back")
    a.expect("Coffee", "You're owed $6.00")


@flow("F09", "Duplicate an expense")
def f09(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Dupes")
    add_members(a, "Bob")
    add_expense(a, "8", "Lunch")
    a.tap("Actions for Lunch")
    a.tap("Duplicate")
    a.expect("New expense")
    a.hide_keyboard()
    a.tap("Save expense")
    a.expect("You're owed $8.00")


@flow("F11", "Search expenses")
def f11(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Search")
    add_members(a, "Bob")
    add_expense(a, "5", "Bread")
    add_expense(a, "7", "Cheese")
    type_into(a, "Search expenses", "Bre")
    a.expect("Bread")
    a.expect_gone("Cheese")
    a.type("zzz")
    a.expect("No matches")
    a.hide_keyboard()


@flow("F12", "Balances: who owes whom, and the opt-in simplify view")
def f12(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Balances")
    add_members(a, "Bob")
    add_expense(a, "40", "Groceries")
    a.tap("Balances", exact=True)
    a.expect("Who owes whom", "Bob owes Alice", "$20.00", "Simplify debts")
    a.tap("Simplify debts")
    a.expect("Fewer payments that settle the same totals")
    # FR-S5: a balance opens into what it is made of.
    a.tap("Bob", exact=True)
    a.expect("Bob — the details", "Paid for the group", "Share of expenses", "Groceries", "Paid $0.00 · share $20.00")
    a.tap("Back to balances")
    a.expect("Who owes whom")


@flow("F13", "Settle up: suggested payment, partial payment, history")
def f13(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Settle")
    add_members(a, "Bob")
    add_expense(a, "40", "Rent share")
    a.tap("Settle up", exact=True)
    a.expect("Suggested payments", "Bob owes Alice · $20.00")
    a.tap("Record", exact=True)
    a.expect("Record payment")
    a.tap("Record", exact=True, index=1) if len([n for n in a.nodes() if n.text == "Record"]) > 1 else a.tap("Record", exact=True)
    a.expect("All settled up", "Bob paid Alice")
    a.tap("Expenses", exact=True)
    a.expect("Settled up")


@flow("F14", "Share who owes whom as plain text")
def f14(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Reminders")
    add_members(a, "Bob")
    add_expense(a, "24", "Tickets")
    a.tap("More actions")
    a.tap("Share who owes whom")
    # The system share sheet previews the text Dibs handed it.
    a.expect("who owes whom", timeout=15)
    assert any("Bob owes Alice $12.00" in label for label in a.labels()), a.labels()
    a.back()


@flow("F15", "Just this once: a one-off split without creating a group")
def f15(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "One")
    a.tap("Back")
    a.expect("One", "1 member")
    create_group(a, "Two")
    a.tap("Back")
    a.tap("Add expense")
    a.expect("Add expense to…", "One", "Two", "Just this once")
    a.tap("Just this once")
    a.expect("New expense")
    a.back()
    a.tap_if_present("Discard")


@flow("F16", "Archive and unarchive a group")
def f16(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Old trip")
    a.tap("More actions")
    a.tap("Archive group")
    a.tap("Back")
    a.expect("Archived", "Old trip")
    a.tap("Old trip")
    a.tap("More actions")
    a.tap("Unarchive group")
    a.tap("Back")
    a.expect_gone("Archived")


def pick_receipt_image(device: Device) -> None:
    """Scan screen → gallery → the generated receipt in Downloads."""
    from receipt import write_receipt

    local = os.path.join(OUT, "receipt-e2e.png")
    os.makedirs(OUT, exist_ok=True)
    write_receipt(local)
    device.adb("push", local, "/sdcard/Download/receipt-e2e.png")
    device.tap("Pick from gallery")
    device.tap("receipt-e2e.png, ", timeout=20)
    device.expect("1 frame captured", timeout=20)


@flow("F17", "Scan a receipt from the gallery with the camera denied; itemize it; scanning it again warns")
def f17(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Cafe")
    add_members(a, "Bob")
    a.shell("pm revoke app.dibs android.permission.CAMERA", check=False)
    a.launch()
    a.tap("Cafe")
    a.tap("More actions")
    a.tap("Scan receipt")
    # Edge 33: a denied camera is never a dead end.
    a.tap_if_present("Don’t allow")
    a.expect("Camera is off — no problem.", "Pick from gallery", "Type it in instead")
    pick_receipt_image(a)
    a.tap("Read receipt")
    # FR-R3: nothing is saved before this review. The text was read on the device —
    # this build has no INTERNET permission (asserted in F30).
    a.expect("Check what was read", "Adds up", "Latte", "Bagel", "Cookie", "4.50", "3.25", "5.00", "1.02", "13.77", timeout=30)
    a.tap("Use these items")
    a.expect("New expense", "$13.77", "Assign every item to someone")
    type_into(a, "What was it for?", "Cafe run")
    a.hide_keyboard()
    # Payer chips come first, then one Alice/Bob pair per item.
    a.tap("Alice", exact=True, index=1)   # Latte → Alice
    a.tap("Bob", exact=True, index=2)     # Bagel → Bob
    a.scroll_down()
    a.tap_last("Alice")                   # Cookie → shared
    a.tap_last("Bob")
    a.expect("Total from items: $13.77")
    a.tap("Save expense")
    # Alice paid 13.77. Bob: bagel 3.25 + half the cookie 2.50 = 5.75 of 12.75,
    # plus that share of 1.02 tax = 0.46. He owes 6.21, to the cent.
    a.expect("Cafe run", "$13.77", "You're owed $6.21", timeout=20)
    assert a.app_file_list("files/receipts"), "FR-E8: the receipt photo was not kept in app-private storage"
    # Edge 47: the same paper scanned twice is noticed.
    a.tap("More actions")
    a.tap("Scan receipt")
    pick_receipt_image(a)
    a.tap("Read receipt")
    a.expect("This looks identical to a receipt already in the group", timeout=30)
    a.back()


@flow("F26", "Settings: theme and name persist across a restart")
def f26(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    a.tap("Settings", exact=True)
    a.expect("Appearance", "Defaults", "Privacy & security", "Backup")
    a.tap("Dark", exact=True)
    a.force_stop()
    a.launch()
    a.tap("Settings", exact=True)
    a.expect("Alice", "Dark")
    # The switches are labelled rows now: tapping the words toggles them (NFR-10).
    a.tap("Block screenshots")
    a.scroll_down()
    a.expect("Dibs collects nothing", "Apache-2.0", "Version")
    a.shell("input swipe 540 700 540 1700 250")
    a.tap("Block screenshots")
    a.tap("System", exact=True)


@flow("F29", "Lifecycle: rotation, 200% font, dark mode, and process death keep an expense draft")
def f29(a: Device, b: Device) -> None:
    fresh(a, "Alice")
    create_group(a, "Lifecycle")
    add_members(a, "Bob")
    a.tap("Add expense")
    a.type("42")
    type_into(a, "What was it for?", "Draft")
    a.hide_keyboard()
    try:
        a.rotate(True)
        a.expect("Draft")
        a.rotate(False)
        a.expect("Draft")
        a.set_font_scale(2.0)
        a.expect("Draft")
        a.set_dark(True)
        a.expect("Draft")
        # Edge 61: the OS kills the process in the background; the draft must come back.
        a.kill_process()
        a.launch()
        a.expect("Draft", timeout=20)
    finally:
        a.set_font_scale(1.0)
        a.set_dark(False)
        a.rotate(False)
    a.hide_keyboard()
    a.tap("Save expense")
    a.expect("Draft", "$42.00")


@flow("F30", "Airplane mode: everything on one phone still works")
def f30(a: Device, b: Device) -> None:
    a.airplane(True)
    try:
        fresh(a, "Alice")
        create_group(a, "Offline")
        add_members(a, "Bob")
        add_expense(a, "9", "Snacks")
        a.tap("Balances", exact=True)
        a.expect("Bob owes Alice")
        perms = a.shell("dumpsys package app.dibs | grep -c 'android.permission.INTERNET'", check=False).strip()
        assert perms == "0", "the app declares the INTERNET permission"
    finally:
        a.airplane(False)


# -- runner ------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("flows", nargs="*", help="flow ids to run (default: all)")
    parser.add_argument("--a", default="emulator-5554", help="first device serial")
    parser.add_argument("--b", default="emulator-5556", help="second device serial")
    parser.add_argument("--apk", help="debug APK to install on both devices first")
    args = parser.parse_args()

    a, b = Device(args.a, "A"), Device(args.b, "B")
    for device in (a, b):
        device.wait_for_boot()
        if args.apk:
            device.install(args.apk)

    import flows_sync  # noqa: F401  registers the two-phone flows

    wanted = args.flows or list(FLOWS)
    unknown = [f for f in wanted if f not in FLOWS]
    if unknown:
        print(f"unknown flow(s): {unknown}; known: {sorted(FLOWS)}")
        return 2

    failed = []
    for flow_id in wanted:
        title, function = FLOWS[flow_id]
        started = time.time()
        try:
            function(a, b)
            print(f"PASS  {flow_id}  {title}  ({time.time() - started:.0f}s)")
        except Exception as error:  # noqa: BLE001 - every failure is reported, none hidden
            failed.append(flow_id)
            print(f"FAIL  {flow_id}  {title}")
            print(f"      {type(error).__name__}: {error}")
            if not isinstance(error, StepFailed):
                traceback.print_exc()
            for device in (a, b):
                device.screenshot(os.path.join(OUT, f"{flow_id}-{device.name}-failure.png"))
    print(f"\n{len(wanted) - len(failed)} passed, {len(failed)} failed" + (f": {failed}" if failed else ""))
    return len(failed)


if __name__ == "__main__":
    sys.exit(main())
