"""One-time source changes following observed Android and host failures."""
from pathlib import Path
import hashlib,json
root=Path.cwd()
def edit(path,old,new):
 p=root/path;s=p.read_text();assert old in s,(path,old[:80]);p.write_text(s.replace(old,new))
p='app/src/main/java/org/lumaview/mobile/viewport/RoiOverlayView.kt'
edit(p,'if(e.actionMasked==MotionEvent.ACTION_CANCEL){owner="";activeId=-1;return true}','if(e.actionMasked==MotionEvent.ACTION_CANCEL){if(owner=="seek")onSeek(0f,true);owner="";activeId=-1;return true}')
edit(p,'if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN){owner="pinch";return true}','if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN){if(owner=="seek")onSeek(0f,true);owner="pinch";return true}')
edit(p,'if(index<0){owner="";return true}','if(index<0){if(owner=="seek")onSeek(0f,true);owner="";activeId=-1;return true}')
t='app/src/androidTest/java/org/lumaview/mobile/PlayerIntegrationTest.kt'
s=(root/t).read_text();a=s.index(' private fun grayFixture(');b=s.index(' @Test fun roiPixels',a)
s=s[:a]+''' private fun grayFixture(name:String,inside:Int,outside:Int):File {
  // Exercise the product's video contract. Single PNG image streams do not
  // refresh video-crop in the pinned upstream still-image path.
  val asset="roi-gray-$inside-bg-$outside.mp4"
  val f=File(context.filesDir,asset)
  inst.context.assets.open(asset).use{input->f.outputStream().use{input.copyTo(it)}}
  return f
 }
'''+s[b:]
s=s.replace('waitFor("source crop applied"){value("crop")!=null};snapshot("03-roi")','''waitFor("source crop applied"){
    val c=value("crop") as? RoiRect;val r=session.state.receipt
    c!=null&&r!=null&&kotlin.math.abs(r[8]-c.left)<3&&kotlin.math.abs(r[9]-c.top)<3&&kotlin.math.abs(r[10]-c.right)<3&&kotlin.math.abs(r[11]-c.bottom)<3
   };snapshot("03-roi")''')
# Keep a real Android cancellation check in addition to the host event-routing red/green test.
s=s.replace('   tap("区域放大");assertNull', '''   val gb=bounds(value("videoFrame") as View);val down=SystemClock.uptimeMillis()
   touch(gb.exactCenterX(),gb.exactCenterY(),MotionEvent.ACTION_DOWN,down,down)
   touch(gb.exactCenterX()+70,gb.exactCenterY(),MotionEvent.ACTION_MOVE,down+80,down)
   Thread.sleep(200);assertEquals(true,value("dragging"))
   touch(gb.exactCenterX()+70,gb.exactCenterY(),MotionEvent.ACTION_CANCEL,down+300,down)
   waitFor("cancelled gesture releases scrub ownership"){value("dragging")==false}
   waitFor("cancelled seek restores pause"){session.state.paused}
   tap("区域放大");assertNull''')
s=s.replace('.put("volumePropertyVerified",true)', '.put("volumePropertyVerified",true).put("cancelledSeekReleasesOwnership",true)')
(root/t).write_text(s)
p=root/'ci/prepare_release.py';s=p.read_text()
s+='''
# Paired source-video fixtures: fixed dark central ROI, independently changing outside.
for inside,outside in [(96,0),(96,255),(72,255)]:
    color='0x%02x%02x%02x'%(inside,inside,inside)
    dest=evd/('roi-gray-%d-bg-%d.mp4'%(inside,outside))
    subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i',
      'color=c=%s:s=640x360:r=30:d=10'%('white' if outside else 'black'),
      '-vf','drawbox=x=140:y=70:w=360:h=220:color=%s:t=fill,format=yuv420p'%color,
      '-c:v','libx264','-preset','fast','-crf','12','-g','60','-an','-y',str(dest)],check=True)
'''
p.write_text(s)
files=['app/src/main/java/org/lumaview/mobile/viewport/RoiOverlayView.kt',t,'ci/prepare_release.py']
(root/'ci/final-fixes-applied.json').write_text(json.dumps({name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in files},indent=2))
print('Applied cancelled-gesture fix and real-video ROI fixtures')
