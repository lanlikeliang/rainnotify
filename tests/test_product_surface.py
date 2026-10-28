"""Security contract for the API-only production package, independent of device accounts."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'

class ProductSurfaceTest(unittest.TestCase):
    def setUp(self):
        self.manifest = ET.parse(ROOT / 'android-app/app/src/main/AndroidManifest.xml').getroot()

    def test_only_launcher_activity_is_exported(self):
        activities = self.manifest.findall('application/activity')
        self.assertEqual(['.MainActivity'], [a.get(ANDROID + 'name') for a in activities])

    def test_no_accessibility_overlay_or_unlock_surface(self):
        services = self.manifest.findall('application/service')
        self.assertEqual({'.monitor.LessonMonitorService', '.answer.AnswerService'},
                         {s.get(ANDROID + 'name') for s in services})
        self.assertTrue(all(s.get(ANDROID + 'exported') == 'false' for s in services))
        permissions = {p.get(ANDROID + 'name') for p in self.manifest.findall('uses-permission')}
        self.assertFalse(permissions & {'android.permission.SYSTEM_ALERT_WINDOW',
            'android.permission.DISABLE_KEYGUARD', 'android.permission.USE_FULL_SCREEN_INTENT'})

    def test_only_wechat_is_discoverable_for_user_authorization(self):
        self.assertEqual(['com.tencent.mm'],
            [p.get(ANDROID + 'name') for p in self.manifest.findall('queries/package')])

if __name__ == '__main__':
    unittest.main()
