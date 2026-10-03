from pathlib import Path
import os,sys,subprocess,hashlib,json,zipfile,re
arch=sys.argv[1];abi='arm64-v8a' if arch=='arm64' else 'x86_64'
ev=Path('release-evidence');out=Path('release-out');out.mkdir(exist_ok=True)
source=list(Path('app/build/outputs/apk/default/debug').glob(f'*{abi}*.apk'))
assert len(source)==1,source
apk=out/('LumaView-Mobile-0.1.0-test-'+abi+'.apk');apk.write_bytes(source[0].read_bytes())
tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
def run(cmd):return subprocess.check_output(list(map(str,cmd)),stderr=subprocess.STDOUT,text=True)
(ev/'apk-signature.txt').write_text(run([tools/'apksigner','verify','--verbose','--print-certs',apk]))
(ev/'zipalign.txt').write_text(run([tools/'zipalign','-c','-P','16','-v','4',apk]))
badging=run([tools/'aapt2','dump','badging',apk]);(ev/'apk-manifest.txt').write_text(badging)
assert "name='org.lumaview.mobile'" in badging
assert "versionName='0.1.0-test'" in badging
assert re.search(r"(?m)^(?:minSdkVersion|sdkVersion):'23'\s*$",badging), 'minimum SDK must be exactly 23'
assert re.search(r"(?m)^targetSdkVersion:'36'\s*$",badging), 'target SDK must be exactly 36'
for permission in ['INTERNET','CAMERA','RECORD_AUDIO','MANAGE_EXTERNAL_STORAGE','SYSTEM_ALERT_WINDOW']:assert 'android.permission.'+permission not in badging,permission
elf=[]
with zipfile.ZipFile(apk) as z:
 assert z.testzip() is None
 names=[n for n in z.namelist() if n.endswith('.so')];assert names
 assert {n.split('/')[1] for n in names}=={abi}
 for name in names:
  p=ev/Path(name).name;p.write_bytes(z.read(name));detail=run([Path(os.environ['ANDROID_NDK_ROOT'])/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf','-lW',p]);(ev/(p.name+'.elf.txt')).write_text(detail)
  loads=[line for line in detail.splitlines() if re.match(r'\s*LOAD\s',line)]
  assert loads and all(int(line.split()[-1],16)>=16384 for line in loads),(name,loads)
  elf.append({'name':name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'loadAlignment16K':True});p.unlink()
manifest={'version':'0.1.0-test','abi':abi,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'sourceCommit':run(['git','rev-parse','HEAD']).strip(),'elf':elf,'targetPhysicalDeviceTest':'NOT_RUN','standaloneFontsIncluded':False}
(ev/'BUILD_RESULT.json').write_text(json.dumps(manifest,indent=2));(out/'SHA256SUMS.txt').write_text(manifest['apkSha256']+'  '+apk.name+'\n')
print(json.dumps(manifest,indent=2))
