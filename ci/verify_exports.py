"""Inspect actual Android exports by track type, never by stream-list position."""
from pathlib import Path
from collections import Counter
import hashlib,json,subprocess,sys

def validate_exact(data, expected, description):
    assert abs(float(data['format']['duration'])-expected)<.12
    video=[s for s in data['streams'] if s['codec_type']=='video']
    audio=[s for s in data['streams'] if s['codec_type']=='audio']
    assert len(video)==1,video
    assert video[0]['codec_name']=='h264'
    assert video[0]['width']==640 and video[0]['height']==360
    assert all(s['codec_name']=='aac' for s in audio)
    assert '视频编码器：' in description

def main(out:Path,source:Path):
    report=json.loads((out/'RESULT.json').read_text())
    def probe(path):return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-show_packets','-show_data_hash','sha256','-of','json',str(path)]))
    original=probe(source);checks={}
    for mode in ['copy','exact']:
        path=out/(mode+'.mp4');data=probe(path)
        decoded=subprocess.run(['ffmpeg','-v','error','-xerror','-i',str(path),'-f','null','-'],capture_output=True,text=True)
        assert decoded.returncode==0,(mode,decoded.stderr)
        checks[mode]={'fullyDecoded':True,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'durationSeconds':float(data['format']['duration']),'streams':[{k:s.get(k) for k in ['index','codec_name','codec_type','width','height','sample_rate']} for s in data['streams']]}
        if mode=='copy':
            for stream in data['streams']:
                typ=stream['codec_type'];src=next(s['index'] for s in original['streams'] if s['codec_type']==typ)
                source_packets=[p for p in original['packets'] if p['stream_index']==src]
                packets=[p for p in data['packets'] if p['stream_index']==stream['index']]
                assert packets,'Empty copied track: '+typ
                counts=Counter(p['data_hash'] for p in source_packets)
                for p in packets:
                    assert counts[p['data_hash']]>0,'Missing or over-reused encoded packet: '+typ
                    counts[p['data_hash']]-=1
                checks[mode][typ+'PayloadsUnchanged']=True
                checks[mode][typ+'CopiedPackets']=len(packets)
        else:
            expected=(report['uiRangeEndUs']-report['uiRangeStartUs'])/1e6
            validate_exact(data,expected,report['exactResult'])
            checks[mode]['requestedDurationSeconds']=expected
    checks['sourceSha256']=hashlib.sha256(source.read_bytes()).hexdigest()
    checks['huaweiPhysicalValidation']='NOT_RUN'
    checks['environment']='Actual retained outputs from Android API35 x86_64 emulator; host full decode and packet validation'
    print(json.dumps(checks,indent=2))
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]))
