"""Prepare source-built native prefix and rebuild our modified libmpv/JNI."""
from pathlib import Path
import os,sys,tarfile,subprocess,json,urllib.request,hashlib,shutil
arch=sys.argv[1];root=Path.cwd();ev=root/'release-evidence';ev.mkdir(exist_ok=True)
source=root/'native-input/native-evidence'
lock=json.loads((source/'dependencies-lock.json').read_text())
assert lock['mpv']['commit']=='0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6'
assert lock['ffmpeg']['commit']=='094a2f8a2a5e7fa64736e067de224ce28fdf5979'
# Extract only the upstream build scripts; never overwrite our app sources.
with tarfile.open(source/'upstream-application-source.tar.gz') as t:
 for member in t.getmembers():
  if member.name.removeprefix('./').startswith('buildscripts/'):
   t.extract(member,root,filter='data')
with tarfile.open(source/f'fixed-dependency-sources-{arch}.tar.gz') as t:t.extractall(root/'buildscripts',filter='data')
with tarfile.open(source/f'prefix-{arch}.tar.gz') as t:t.extractall(root/'buildscripts',filter='data')
# The reusable source archive intentionally excludes standalone font files.
# Fetch the fixed upstream OSD data solely as an internal compiler input.
font=root/'buildscripts/deps/mpv/sub/osd_font.otf'
if not font.exists():
 with urllib.request.urlopen('https://raw.githubusercontent.com/mpv-player/mpv/0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6/sub/osd_font.otf') as r:data=r.read()
 assert hashlib.sha256(data).hexdigest()=='b2bd8f34ac4afa0a4ca24d7a94e06589ed783ea0ded16e512b2f8f7119c28a1f'
 font.write_bytes(data)
sdk=root/'buildscripts/sdk';sdk.mkdir(exist_ok=True)
for name,value in [('android-sdk-linux',os.environ['ANDROID_HOME']),('android-ndk-r30',os.environ['ANDROID_NDK_ROOT'])]:
 p=sdk/name
 if p.is_symlink():p.unlink()
 if not p.exists():p.symlink_to(value,target_is_directory=True)
# Reuse every other native prefix; enable MP4 in the same fixed FFmpeg source.
ffscript=root/'buildscripts/scripts/ffmpeg.sh';config=ffscript.read_text()
assert '--enable-muxer=mov,matroska,mpegts' in config
ffscript.write_text(config.replace('--enable-muxer=mov,matroska,mpegts','--enable-muxer=mov,mp4,matroska,mpegts'))
with (ev/f'ffmpeg-{arch}.log').open('w') as f:subprocess.run(['./buildall.sh','-n','--arch',arch,'ffmpeg'],cwd=root/'buildscripts',env=dict(os.environ,cores='4'),stdout=f,stderr=subprocess.STDOUT,check=True)
(ev/'ffmpeg-export-config.json').write_text(json.dumps({'source':'094a2f8a2a5e7fa64736e067de224ce28fdf5979','change':'MP4 muxer enabled; other native dependencies reused'},indent=2))
subprocess.run([sys.executable,'ci/patch_engine.py'],check=True)
env=dict(os.environ,cores='4')
with (ev/f'engine-{arch}.log').open('w') as f:subprocess.run(['./buildall.sh','-n','--arch',arch,'mpv'],cwd=root/'buildscripts',env=env,stdout=f,stderr=subprocess.STDOUT,check=True)
prefix=root/f'buildscripts/prefix/{arch}'
ndk=Path(os.environ['ANDROID_NDK_ROOT']);env['PREFIX64' if arch=='arm64' else 'PREFIX_X64']=str(prefix)
with (ev/f'jni-{arch}.log').open('w') as f:subprocess.run([str(ndk/'ndk-build'),'-C','app/src/main','-j4'],env=env,stdout=f,stderr=subprocess.STDOUT,check=True)
(root/'ndk.properties').write_text('ndkVersion=30.0.16248370\nndkRoot='+str(ndk)+'\n')
(root/'local.properties').write_text('sdk.dir='+os.environ['ANDROID_HOME']+'\n')
asset=root/'app/src/main/assets/lvm/mobile.glsl';asset.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(root/'shaders/lvm/mobile.glsl',asset)
(evd:=root/'app/src/androidTest/assets').mkdir(parents=True,exist_ok=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=640x360:rate=30:duration=12','-f','lavfi','-i','sine=frequency=440:duration=12','-vf','format=rgb24,colorchannelmixer=rr=0.18:gg=0.18:bb=0.18,format=yuv420p','-c:v','libx264','-preset','fast','-crf','18','-g','60','-keyint_min','60','-sc_threshold','0','-c:a','aac','-shortest','-y',str(evd/'baseline.mp4')],check=True)
(evd/'fixture.sha256').write_text(hashlib.sha256((evd/'baseline.mp4').read_bytes()).hexdigest())
shutil.copyfile(source/'dependencies-lock.json',ev/'dependencies-lock.json')
print('Prepared native and fixture:',arch)
