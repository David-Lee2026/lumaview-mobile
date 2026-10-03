"""Reuse this project's exact native build; do not substitute third-party APK code."""
from pathlib import Path
import hashlib,json,os,subprocess,sys,zipfile
arch=sys.argv[1]
abi={'arm64':'arm64-v8a','x86_64':'x86_64'}[arch]
base=Path('base-build');ev=Path('release-evidence');ev.mkdir(exist_ok=True)
record=json.loads((base/'release-evidence/BUILD_RESULT.json').read_text())
assert record['sourceCommit']=='bb4a7162d6b5ffe334909559c4618f426a1da21d'
assert record['abi']==abi
apk=next((base/'release-out').glob('*.apk'))
assert hashlib.sha256(apk.read_bytes()).hexdigest()==record['apkSha256']
# Kotlin-only follow-up: native source and shader identity must match the native build.
paths=['app/src/main/jni','ci/patch_engine.py','shaders/lvm/mobile.glsl','app/src/main/assets/lvm/mobile.glsl']
assert not subprocess.check_output(['git','diff','--name-only',record['sourceCommit'],'HEAD','--',*paths],text=True).strip()
with zipfile.ZipFile(apk) as z:
 assert z.testzip() is None
 native=[n for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')]
 assert set(native)=={entry['name'] for entry in record['elf']}
 for entry in record['elf']:
  name=entry['name'];assert name.startswith('lib/'+abi+'/')
  data=z.read(name);assert hashlib.sha256(data).hexdigest()==entry['sha256']
  out=Path('app/src/main/jniLibs')/abi/Path(name).name;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(data)
(ev/'native-reuse.json').write_text(json.dumps({'baseRun':37141335660,'base':record,'reason':'Only Kotlin UI and Android tests changed; native sources and shaders checked identical'},indent=2))
Path('local.properties').write_text('sdk.dir='+os.environ['ANDROID_HOME']+'\n')
Path('ndk.properties').write_text('ndkVersion=30.0.16248370\nndkRoot='+os.environ['ANDROID_NDK_ROOT']+'\n')
assets=Path('app/src/androidTest/assets');assets.mkdir(parents=True,exist_ok=True)
subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=640x360:rate=30:duration=12','-f','lavfi','-i','sine=frequency=440:duration=12','-vf','format=rgb24,colorchannelmixer=rr=0.18:gg=0.18:bb=0.18,format=yuv420p','-c:v','libx264','-preset','fast','-crf','18','-g','60','-keyint_min','60','-sc_threshold','0','-c:a','aac','-shortest','-y',str(assets/'baseline.mp4')],check=True)
(assets/'fixture.sha256').write_text(hashlib.sha256((assets/'baseline.mp4').read_bytes()).hexdigest())
(ev/'source-commit.txt').write_text(subprocess.check_output(['git','rev-parse','HEAD'],text=True))
print('Verified native reuse for',abi)
