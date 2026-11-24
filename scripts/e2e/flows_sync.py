# SPDX-License-Identifier: Apache-2.0
"""Two-phone flows (F21–F25): invite, join, share updates, converge.

The phones never talk to each other — there is no server. A file one phone
stages for its share sheet is pulled off it and placed on the other, which is
exactly what sending it through a chat app does.
"""
from __future__ import annotations

import time

from run import add_expense, add_members, create_group, flow, fresh, type_into
from ui import Device, StepFailed

INVITE_FILE = "dibs-invite.dibsinvite"


def wait_for_staged(device: Device, name: str, newer_than: float = 0.0) -> bytes:
    """Waits for the app to stage a file for sharing, then dismisses the share sheet."""
    deadline = time.time() + 15
    while time.time() < deadline:
        if name in device.app_file_list("cache/sync"):
            data = device.pull_staged(name)
            if data:
                time.sleep(1)
                device.back()  # close the system share sheet; the file is what we came for
                return data
        time.sleep(0.5)
    raise StepFailed(f"[{device.name}] '{name}' was never staged; cache/sync has {device.app_file_list('cache/sync')}")


def clear_staged(device: Device) -> None:
    device.shell("run-as app.dibs rm -rf cache/sync", check=False)


def deliver(device: Device, name: str, data: bytes) -> None:
    """Hands the phone a file as if it had arrived in a chat, and opens it with Dibs."""
    device.push_staged(name, data)
    device.open_file(name)


def link_in(invite_file: bytes) -> str:
    """The invite link sits in the clear at the front of an invite file (see InviteFile)."""
    assert invite_file[:8] == b"DIBSINV1", "not an invite file"
    length = int.from_bytes(invite_file[8:10], "big")
    return invite_file[10:10 + length].decode()


def group_slug_file(group: str) -> str:
    return "dibs-" + "-".join(group.lower().split()) + ".dibssync"


def alice_creates_group(a: Device, group: str) -> None:
    fresh(a, "Alice")
    create_group(a, group)
    add_members(a, "Bob")
    add_expense(a, "30", "Pizza")


def alice_makes_invite(a: Device) -> bytes:
    clear_staged(a)
    a.tap("Members", exact=True)
    a.tap("Invite", exact=True)
    a.expect("Invite to this group", "QR code for the invite link", "For one person, valid until")
    # Exact: the dialog's explanation also contains the words "Send invite".
    a.tap("Send invite", exact=True)
    invite = wait_for_staged(a, INVITE_FILE)
    a.tap("Done")
    a.tap("Back")
    return invite


def share_updates(sender: Device, group: str) -> bytes:
    clear_staged(sender)
    sender.tap("More actions")
    sender.tap("Share updates")
    return wait_for_staged(sender, group_slug_file(group))


def open_updates(receiver: Device, data: bytes, expect_new: bool = True) -> None:
    deliver(receiver, f"incoming-{int(time.time() * 1000)}.dibssync", data)
    receiver.expect("Updates added")
    if expect_new:
        receiver.expect("new change")
    receiver.tap("Open group")


def bob_joins(a: Device, b: Device, group: str, invite: bytes) -> None:
    fresh(b, "Bob")
    deliver(b, "incoming.dibsinvite", invite)
    b.expect(f"Join {group}", "Invited by Alice", "Which one is you?", "I'm not on the list")
    b.tap("Bob", exact=True)
    b.tap("Join", exact=True)
    b.expect(f"You're in {group}", "Tell the group I joined")
    clear_staged(b)
    b.tap("Tell the group I joined")
    join_bundle = wait_for_staged(b, group_slug_file(group))
    b.tap("Open group")
    b.expect("Pizza", "You owe $15.00")
    # Alice hears about it only when she opens what Bob sent.
    open_updates(a, join_bundle)


@flow("F21", "Invite: QR code shown, invite file staged for sharing")
def f21(a: Device, b: Device) -> None:
    alice_creates_group(a, "Goa trip")
    invite = alice_makes_invite(a)
    assert invite[:8] == b"DIBSINV1", "the shared file is not an invite file"
    assert link_in(invite).startswith("dibs://join?g="), link_in(invite)
    a.tap("Members", exact=True)
    a.expect("Invite open until", "Cancel invite")
    a.tap("Back")


@flow("F22", "Join from an invite file in one step; both phones then agree")
def f22(a: Device, b: Device) -> None:
    alice_creates_group(a, "Goa trip")
    bob_joins(a, b, "Goa trip", alice_makes_invite(a))
    a.expect("Bob joined this group.", timeout=3) if a.has("Bob joined this group.") else None
    a.tap("Members", exact=True)
    a.expect("Bob", "Joined with your invite")
    fingerprints = [label for label in a.labels() if label.startswith("#")]
    assert len(fingerprints) == 2, f"both members should be claimed with fingerprints, saw {fingerprints}"
    a.tap("Back")
    a.expect("You're owed $15.00")
    b.tap("Members", exact=True)
    b.expect("Alice", "Bob")
    assert sorted(l for l in b.labels() if l.startswith("#")) == sorted(fingerprints), "fingerprints differ between phones"
    b.tap("Back")


@flow("F22b", "Join from a bare link: asks for the group's updates, survives process death, then joins as new")
def f22b(a: Device, b: Device) -> None:
    alice_creates_group(a, "Flat 4B")
    invite = alice_makes_invite(a)
    fresh(b, "Robert")
    b.open_link(link_in(invite))
    b.expect("One more step", "Open updates file")
    # Edge 61: the invite must not be lost if the app is killed while waiting for the file.
    b.kill_process()
    b.launch()
    b.expect("One more step", timeout=20)
    updates = share_updates(a, "Flat 4B")
    deliver(b, "incoming.dibssync", updates)
    b.expect("Join Flat 4B", "Which one is you?")
    b.tap("I'm not on the list")
    b.expect("Robert")  # the name from onboarding is offered
    b.tap("Join", exact=True)
    b.expect("You're in Flat 4B")
    b.tap("Open group")
    b.tap("Members", exact=True)
    b.expect("Robert", "Alice", "Bob")
    b.tap("Back")


@flow("F22c", "Bad invites: a non-link, a non-Dibs file, and updates for a group never joined")
def f22c(a: Device, b: Device) -> None:
    alice_creates_group(a, "Secret")
    updates = share_updates(a, "Secret")
    fresh(b, "Mallory")
    b.tap("Join a group")
    b.expect("Got an invite?", "Open invite file", "Scan a code")
    type_into(b, "Invite link", "https://example.com/not-dibs")
    b.hide_keyboard()
    b.tap("Continue")
    b.expect("That didn't work", "That isn't a Dibs invite link")
    b.tap("Start over")
    b.expect("Got an invite?")
    deliver(b, "holiday.dibssync", b"these are holiday photos, not a Dibs file")
    b.expect("That didn't work", "That file isn't a Dibs invite or update.")
    deliver(b, "secret.dibssync", updates)
    b.expect("That didn't work", "This update is for a group you haven't joined yet")
    # Nothing leaked into the phone: no group appeared.
    b.tap("Back")
    b.tap_if_present("Back")
    b.launch()
    b.expect("No groups yet")


@flow("F23", "Share updates and open them on the other phone; the sync chip tells the truth")
def f23(a: Device, b: Device) -> None:
    alice_creates_group(a, "Goa trip")
    bob_joins(a, b, "Goa trip", alice_makes_invite(a))
    add_expense(a, "10", "Beer")
    # Two things Alice holds that she has not sent on: Bob's join, and the beer.
    a.expect("2 changes to share")
    updates = share_updates(a, "Goa trip")
    a.expect("Shared · up to date")
    open_updates(b, updates)
    b.expect("Beer", "Pizza", "You owe $20.00")
    # Opening the same file again adds nothing and says so (edge 23).
    deliver(b, "again.dibssync", updates)
    b.expect("Updates added", "You already had everything in this file.")
    b.tap("Open group")


@flow("F24", "Both phones change things offline; after exchanging files they agree")
def f24(a: Device, b: Device) -> None:
    alice_creates_group(a, "Goa trip")
    bob_joins(a, b, "Goa trip", alice_makes_invite(a))
    # Offline, at the same time: each adds an expense; Alice deletes Pizza while Bob renames it.
    add_expense(a, "8", "Sunscreen")
    add_expense(b, "6", "Water")
    a.tap("Pizza")
    a.expect("Edit expense")
    a.tap("Delete expense")
    a.expect_gone("Pizza")
    b.tap("Pizza")
    type_into(b, "Pizza", " large")
    b.hide_keyboard()
    b.tap("Save expense")
    b.expect("Pizza large")

    from_a = share_updates(a, "Goa trip")
    from_b = share_updates(b, "Goa trip")
    open_updates(b, from_a)
    open_updates(a, from_b)

    for phone in (a, b):
        phone.expect("Sunscreen", "Water")
        assert not phone.has("Pizza"), f"[{phone.name}] edge 21: the deleted expense is back: {phone.labels()}"
    # Sunscreen 8 paid by Alice, Water 6 paid by Bob, split two ways: Bob owes Alice 1.00.
    a.expect("You're owed $1.00")
    b.expect("You owe $1.00")
    # The edit made on the other phone was kept, and is there if the expense is restored.
    a.tap("More actions")
    a.tap("Trash")
    a.expect("Pizza large")
    a.tap("Back")


@flow("F25", "Approval mode: a joiner waits, is approved, and can be removed while they have no history")
def f25(a: Device, b: Device) -> None:
    alice_creates_group(a, "Book club")
    a.tap("Members", exact=True)
    a.tap("Approve new members")
    a.tap("Back")
    invite = alice_makes_invite(a)

    fresh(b, "Cara")
    deliver(b, "incoming.dibsinvite", invite)
    b.expect("Join Book club", "This group approves new members")
    b.tap("I'm not on the list")
    b.tap("Join", exact=True)
    b.expect("You're in Book club", "Someone in the group still needs to approve you")
    clear_staged(b)
    b.tap("Tell the group I joined")
    join_bundle = wait_for_staged(b, group_slug_file("Book club"))

    open_updates(a, join_bundle)
    a.tap("Members", exact=True)
    a.expect("Cara", "Waiting to be approved", "Approve")
    a.tap("Approve", exact=True)
    a.expect_gone("Waiting to be approved")
    a.expect("Joined with your invite")
    # Cara has no expenses: removal is offered. Bob owes for the pizza: it is not.
    a.tap("Actions for Bob")
    assert not a.has("Remove from group"), "edge 13: a member with history must not be removable"
    a.back()
    a.tap("Actions for Cara")
    a.tap("Remove from group")
    a.expect_gone("Cara")
    a.tap("Back")
