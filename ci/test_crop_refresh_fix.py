from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from ci.crop_refresh_fix import apply

class CropPatchTest(unittest.TestCase):
 def test_fixes_both_core_readers_idempotently(self):
  with TemporaryDirectory() as d:
   p=Path(d);(p/'player').mkdir()
   (p/'player/video.c').write_text('struct m_geometry *gm = &vo->opts->video_crop;')
   (p/'player/command.c').write_text('struct m_geometry *gm = &mpctx->video_out->opts->video_crop;')
   apply(p);a=(p/'player/video.c').read_bytes();b=(p/'player/command.c').read_bytes();apply(p)
   self.assertEqual(a,b);self.assertEqual(a,(p/'player/video.c').read_bytes());self.assertEqual(b,(p/'player/command.c').read_bytes())
   self.assertIn(b'&mpctx->opts->vo->video_crop',a)
 def test_refuses_missing_anchor(self):
  with TemporaryDirectory() as d:
   p=Path(d);(p/'player').mkdir();(p/'player/video.c').write_text('different upstream version')
   with self.assertRaises(ValueError):apply(p)
if __name__=='__main__':unittest.main()
