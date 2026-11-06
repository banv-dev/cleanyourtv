#!/usr/bin/env python3
"""Validate optional googleDrive theme content before a theme push; never reads credentials."""
import json
import re
import sys


def validate(theme):
    if "googleDrive" not in theme:
        return
    drive = theme["googleDrive"]
    if not isinstance(drive, dict):
        raise ValueError("googleDrive must be an object")
    if "enabled" in drive and type(drive["enabled"]) is not bool:
        raise ValueError("googleDrive.enabled must be boolean")
    if drive.get("enabled") is False:
        return
    folder = drive.get("folderId")
    if not isinstance(folder, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", folder):
        raise ValueError("googleDrive.folderId must be a nonempty folder ID, not a URL")
    interval = drive.get("syncInterval", 360)
    if type(interval) is not int or not 0 <= interval <= 2147483647:
        raise ValueError("googleDrive.syncInterval must be a nonnegative integer in minutes")
    retention = drive.get("cacheRetentionDays", 30)
    if type(retention) is not int or not 1 <= retention <= 2147483647:
        raise ValueError("googleDrive.cacheRetentionDays must be a positive integer in days")
    if "syncOnLaunch" in drive and type(drive["syncOnLaunch"]) is not bool:
        raise ValueError("googleDrive.syncOnLaunch must be boolean")


if __name__ == "__main__":
    try:
        with open(sys.argv[1], encoding="utf-8-sig") as stream:
            validate(json.load(stream))
    except (OSError, ValueError, TypeError) as error:
        print(f"Invalid theme: {error}. Nothing was sent to the TV.", file=sys.stderr)
        sys.exit(2)
