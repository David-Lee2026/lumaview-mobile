"""Create three reproducible videos, not still-image surrogate decoder paths."""
from pathlib import Path
import subprocess,json,hashlib
out=Path('app/src/androidTest/assets');out.mkdir(parents=True,exist_ok=True)
entries=[]
for name,inside,outside in [('roi-darkbg',96,0),('roi-whitebg',96,255),('roi-inside-dark',72,255)]:
 color=lambda x:'0x'+('%02x'%x)*3
 path=out/(name+'.mp4')
 args=['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i',f'color=c={color(outside)}:size=640x360:rate=30:duration=4','-vf',f'drawbox=x=140:y=70:w=360:h=220:color={color(inside)}:t=fill,setparams=range=limited:color_primaries=bt709:color_trc=bt709:colorspace=bt709','-an','-c:v','libx264','-crf','0','-preset','fast','-g','30','-keyint_min','30','-sc_threshold','0','-pix_fmt','yuv420p','-y',str(path)]
 subprocess.run(args,check=True)
 probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_streams','-show_format','-of','json',str(path)]))
 assert probe['streams'][0]['codec_name']=='h264' and float(probe['format']['duration'])==4.0
 entries.append({'name':path.name,'inside':inside,'outside':outside,'sourceRoi':[160,90,480,270],'contextBandUnchangedPixels':20,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'command':args,'probe':probe})
(out/'roi-fixtures.json').write_text(json.dumps(entries,indent=2))
print('Created',len(entries),'H264 ROI videos')
