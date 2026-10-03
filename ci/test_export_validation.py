import unittest
from ci.verify_exports import validate_exact
class ExportValidationTest(unittest.TestCase):
 def data(self):return {'streams':[{'codec_type':'audio','codec_name':'aac'},{'codec_type':'video','codec_name':'h264','width':640,'height':360}],'format':{'duration':'6.000000'}}
 def test_audio_first_is_valid(self):validate_exact(self.data(),6.0,'视频编码器：c2.android.avc.encoder')
 def test_video_first_is_valid(self):
  d=self.data();d['streams'].reverse();validate_exact(d,6.0,'视频编码器：c2.android.avc.encoder')
 def test_wrong_video_codec_rejected(self):
  d=self.data();d['streams'][1]['codec_name']='hevc'
  with self.assertRaises(AssertionError):validate_exact(d,6.0,'视频编码器：test')
 def test_wrong_dimensions_rejected(self):
  d=self.data();d['streams'][1]['width']=320
  with self.assertRaises(AssertionError):validate_exact(d,6.0,'视频编码器：test')
 def test_duration_mismatch_rejected(self):
  with self.assertRaises(AssertionError):validate_exact(self.data(),4.0,'视频编码器：test')
 def test_missing_actual_encoder_rejected(self):
  with self.assertRaises(AssertionError):validate_exact(self.data(),6.0,'no encoder')
if __name__=='__main__':unittest.main()
