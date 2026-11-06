"""Drive content is refused before the theme tools send anything to a TV."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("theme_drive", Path(__file__).resolve().parents[1] / "tools/theme_drive.py")
DRIVE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVE)


class ThemeDriveTest(unittest.TestCase):
    def test_defaults_disabled_and_manual(self):
        for theme in ({}, {"googleDrive": {"enabled": False}}, {"googleDrive": {"folderId": "folder-1"}},
                      {"googleDrive": {"folderId": "folder-1", "syncInterval": 0, "syncOnLaunch": False, "cacheRetentionDays": 30}}):
            DRIVE.validate(theme)

    def test_bad_types_and_ranges(self):
        for drive in ({"folderId": "abc", "cacheRetentionDays": 0}, {"folderId": "abc", "cacheRetentionDays": -1},
                      {"folderId": "abc", "cacheRetentionDays": True}, {"folderId": "abc", "cacheRetentionDays": 1.5},
                      {"folderId": "abc", "cacheRetentionDays": "30"}, {"folderId": "abc", "cacheRetentionDays": 2147483648}, None, [], {"folderId": ""}, {"folderId": "https://drive.google.com/folders/abc"},
                      {"folderId": "abc", "enabled": 1}, {"folderId": "abc", "syncInterval": True},
                      {"folderId": "abc", "syncInterval": -1}, {"folderId": "abc", "syncInterval": 1.1},
                      {"folderId": "abc", "syncInterval": 2147483648}, {"folderId": "abc", "syncOnLaunch": "true"}):
            with self.subTest(drive=drive), self.assertRaises(ValueError):
                DRIVE.validate({"googleDrive": drive})
