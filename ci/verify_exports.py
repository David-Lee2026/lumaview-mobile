"""Inspect actual Android export outputs and original compressed packet payloads."""
from pathlib import Path
import json,subprocess,sys
out=Path(sys.argv[1]);source=Path(sys.argv[2]);report=json.loads((out/'RESULT.json').read_text())
def probe(path):return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-show_packets','-show_data_hash','sha256','-of','json',str(path)]))
original=probe(source);checks={}
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
  assert data['streams'][0]['codec_name']=='h264'
  assert data['streams'][0]['width']==640 and data['streams'][0]['height']==360
  assert '视频编码器：' in report['exactResult']
checks['huaweiPhysicalValidation']='NOT_RUN';checks['environment']='Android API 35 x86_64 emulator';print(json.dumps(checks,indent=2))
