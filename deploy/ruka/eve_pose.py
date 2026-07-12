"""Runs INSIDE the ruka_hand conda env on the Jetson. Maps EVE's pose whitelist to
RUKA controller calls. Heavy imports (ruka_hand / DYNAMIXEL) are inside main()
so this file is import-clean off-Jetson.

[V6] Implementer note: confirm the real Controller API against
/mnt/ssd/models/RUKA/ruka_hand/control/controller.py on the Jetson and adjust the
calls below (set_pose / reset / constructor) to the actual methods. The
EVE-side hand_tool.py and its tests do NOT depend on this file's internals —
they only assert the bridge argv + the pose whitelist.
"""
import argparse


def main():
    ap = argparse.ArgumentParser(description="Actuate the RUKA hand to a named pose.")
    ap.add_argument("--pose", required=True, choices=["open", "close", "reset"])
    ap.add_argument("--hand", default="right", choices=["right", "left"])
    args = ap.parse_args()

    # ruka_hand env only — never imported in EVE's process.
    from ruka_hand.control.controller import Controller

    ctrl = Controller(hand_type=args.hand)
    if args.pose == "reset":
        ctrl.reset()
    elif args.pose == "open":
        ctrl.set_pose("open")
    elif args.pose == "close":
        ctrl.set_pose("close")
    print(f"{args.pose} {args.hand}")


if __name__ == "__main__":
    main()
