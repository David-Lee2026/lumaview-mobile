"""Validate actual exported media, without treating muxed timestamps as encoding evidence."""
import sys,json,subprocess,hashlib
from pathlib import Path
root=Path(sys.argv[1])
def probe(path):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-show_packets','-show_data_hash','sha256','-of','json',str(path)]))
source=probe(root/'export-fixture.mp4');copy=probe(root/'copy-export.mp4');precise=probe(root/'precise-export.mp4')
checks={}
for name,data in [('copy',copy),('precise',precise)]:
    proc=subprocess.run(['ffmpeg','-v','error','-xerror','-i',str(root/f'{name}-export.mp4'),'-f','null','-'],capture_output=True,text=True)
    assert proc.returncode==0,(name,proc.stderr)
    checks[name]={'fullyDecoded':True,'streams':[{k:s.get(k) for k in ['codec_name','codec_type','width','height','sample_rate']} for s in data['streams']], 'duration':float(data['format']['duration'])}
original={s['index']:s for s in source['streams']}
for st in copy['streams']:
    typ=st['codec_type'];srcidx=next(i for i,s in original.items() if s['codec_type']==typ)
    permitted={p['data_hash'] for p in source['packets'] if p['stream_index']==srcidx}
    packets=[p for p in copy['packets'] if p['stream_index']==st['index']]
    assert packets and all(p['data_hash'] in permitted for p in packets)
    checks['copy'][typ+'PacketPayloadsUnchanged']=True
assert abs(checks['precise']['duration']-2.077778)<.12,checks['precise']
assert [s['codec_name'] for s in precise['streams']]==['h264','aac']
assert checks['precise']['streams'][0]['width']==640 and checks['precise']['streams'][0]['height']==360
checks['huaweiPhysicalValidation']='NOT_RUN';print(json.dumps(checks,indent=2))
