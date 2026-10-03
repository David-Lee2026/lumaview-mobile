"""Inspect actual Android export outputs and original compressed packet payloads."""
from pathlib import Path
import json,subprocess,sys,hashlib
out=Path(sys.argv[1]);source=Path(sys.argv[2]);report=json.loads((out/'RESULT.json').read_text())
def probe(path):return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-show_packets','-show_data_hash','sha256','-of','json',str(path)]))
assert hashlib.sha256(source.read_bytes()).digest()==hashlib.sha256((out.parent/'baseline.mp4').read_bytes()).digest()
original=probe(source);checks={'sourceFixtureSha256':hashlib.sha256(source.read_bytes()).hexdigest()}
for mode in ['copy','exact']:
 path=out/(mode+'.mp4');data=probe(path)
 decoded=subprocess.run(['ffmpeg','-v','error','-xerror','-i',str(path),'-f','null','-'],capture_output=True,text=True)
 assert decoded.returncode==0,(mode,decoded.stderr)
 checks[mode]={'fullyDecoded':True,'durationSeconds':float(data['format']['duration']),'streams':[{k:s.get(k) for k in ['codec_name','codec_type','width','height','sample_rate']} for s in data['streams']]}
 if mode=='copy':
  for stream in data['streams']:
   typ=stream['codec_type'];src=next(s['index'] for s in original['streams'] if s['codec_type']==typ)
   hashes={p['data_hash'] for p in original['packets'] if p['stream_index']==src}
   packets=[p for p in data['packets'] if p['stream_index']==stream['index']]
   assert packets and all(p['data_hash'] in hashes for p in packets)
   checks[mode][typ+'PayloadsUnchanged']=True
 else:
  expected=(report['uiRangeEndUs']-report['uiRangeStartUs'])/1e6
  assert abs(checks[mode]['durationSeconds']-expected)<.12,(expected,checks[mode])
  video=next(s for s in data['streams'] if s['codec_type']=='video')
  assert video['codec_name']=='h264'
  assert video['width']==640 and video['height']==360
  assert all(s['codec_name']=='aac' for s in data['streams'] if s['codec_type']=='audio')
  assert '视频编码器：' in report['exactResult']
roi=json.loads((out/'ROI_RESULT.json').read_text());assert roi['outsideIndependence'] and roi['insideResponse'];checks['roi']=roi
for name in ['DISPLAY_RESULT','RECOVERY_RESULT','PROGRESS_RESULT','HD_RESULT']:
 result=json.loads((out/(name+'.json')).read_text());assert result['physicalDevice'] is False;checks[name]=result
checks['huaweiPhysicalValidation']='NOT_RUN';checks['environment']=json.loads(Path('release-evidence/instrumentation-result.json').read_text())['environment'];print(json.dumps(checks,indent=2))
