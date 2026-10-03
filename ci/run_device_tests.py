"""Run instrumentation explicitly and retain private media/evidence until collection."""
from pathlib import Path
import subprocess,os,re,json,tarfile,hashlib
root=Path.cwd();ev=root/'release-evidence';ev.mkdir(exist_ok=True)
adb=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb';tools=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
def execute(args,timeout=90):
 return subprocess.run(list(map(str,args)),capture_output=True,timeout=timeout)
app=list((root/'release-out').glob('*x86_64.apk'));test=list((root/'app/build/outputs/apk/androidTest/default/debug').glob('*.apk'))
assert len(app)==len(test)==1,(app,test)
for i,apk in enumerate([app[0],test[0]]):
 result=execute([adb,'install','-r',apk]);(ev/f'install-{i}.log').write_bytes(result.stdout+result.stderr);assert result.returncode==0 and b'Success' in result.stdout,result.stderr
badging=subprocess.check_output([str(tools/'aapt2'),'dump','badging',str(test[0])],text=True);(ev/'test-apk-manifest.txt').write_text(badging)
package=re.search(r"package: name='([^']+)'",badging).group(1);assert package=='org.lumaview.mobile.test',package
try:
 result=execute([adb,'shell','am','instrument','-w','-r',package+'/androidx.test.runner.AndroidJUnitRunner'],300)
 (ev/'device-test.log').write_bytes(result.stdout+result.stderr)
 output=(result.stdout+result.stderr).decode('utf-8','replace');match=re.search(r'OK \((\d+) tests?\)',output)
 assert result.returncode==0 and match and int(match.group(1))==4,output[-6000:]
 names=list(dict.fromkeys(re.findall(r'INSTRUMENTATION_STATUS: test=(\w+)',output)))
 assert set(names)=={'realTouchRenderingAndExports','roiStatisticsIgnoreOutsideBrightnessAndRespondInside','rendererFailureRecoversVisibleVideo','progressDoesNotJumpToZeroForMissingPositionSample'},names
 (ev/'instrumentation-result.json').write_text(json.dumps({'environment':'Android API 35 x86_64 emulator','physicalDevice':False,'executedTests':4,'failures':0,'tests':names,'runnerOutputSha256':hashlib.sha256(result.stdout+result.stderr).hexdigest()},indent=2))
finally:
 for name,args in [('logcat.txt',['logcat','-d']),('emulator-properties.txt',['shell','getprop'])]:
  r=execute([adb]+args);(ev/name).write_bytes(r.stdout+r.stderr)
 capture=execute([adb,'exec-out','run-as','org.lumaview.mobile','tar','-C','files','-cf','-','integration','baseline.mp4','roi-scenes.mp4'])
 (ev/'device-integration.tar').write_bytes(capture.stdout);(ev/'device-capture-stderr.txt').write_bytes(capture.stderr)
 assert capture.returncode==0,capture.stderr.decode('utf-8','replace')
 with tarfile.open(ev/'device-integration.tar') as archive:
  names=set(archive.getnames());assert {'integration/RESULT.json','integration/ROI_RESULT.json','integration/copy.mp4','integration/exact.mp4','baseline.mp4','roi-scenes.mp4'}<=names,names
print('4 actual Android instrumentation tests and evidence capture passed')
