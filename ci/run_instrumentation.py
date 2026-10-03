"""Run real Android tests without AGP's automatic post-test uninstall.
Only the ephemeral emulator is selected. Nonzero results and raw files are kept.
"""
from pathlib import Path
import subprocess,json,re,tarfile,sys,hashlib
root=Path.cwd();ev=root/'release-evidence';ev.mkdir(exist_ok=True)
def output(args,timeout=60):
 return subprocess.check_output(args,stderr=subprocess.STDOUT,timeout=timeout)
devices=output(['adb','devices']).decode();serials=[line.split()[0] for line in devices.splitlines()[1:] if line.strip().endswith('\tdevice')]
assert len(serials)==1,devices
adb=['adb','-s',serials[0]]
assert output(adb+['shell','getprop','ro.kernel.qemu']).strip()==b'1','This runner must only install on its test emulator'
apps=list((root/'release-out').glob('*x86_64.apk'));tests=list((root/'app/build/outputs/apk/androidTest/default/debug').glob('*.apk'))
assert len(apps)==len(tests)==1,(apps,tests)
install=[]
for apk in [apps[0],tests[0]]:install.append(output(adb+['install','-r',str(apk)],120).decode())
(ev/'device-install.txt').write_text('\n'.join(install))
cmd=adb+['shell','am','instrument','-w','-r','org.lumaview.mobile.test/androidx.test.runner.AndroidJUnitRunner']
try:
 p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=500)
 text=p.stdout.decode('utf-8','replace');code=p.returncode
except subprocess.TimeoutExpired as e:
 text=(e.stdout or b'').decode('utf-8','replace')+'\nTEST_TIMEOUT';code=124
finally:
 for name,command in [('logcat.txt',['logcat','-d']),('emulator-properties.txt',['shell','getprop'])]:
  try:(ev/name).write_bytes(output(adb+command))
  except Exception as e:(ev/(name+'.error')).write_text(str(e))
 try:(ev/'device-integration.tar').write_bytes(output(adb+['exec-out','run-as','org.lumaview.mobile','tar','-C','files','-cf','-','integration']))
 except Exception as e:(ev/'integration-copy-error.txt').write_text(str(e))
(ev/'device-test.log').write_text(text)
passed=re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',text,re.M)
failed=re.findall(r'^INSTRUMENTATION_STATUS_CODE: -\d+\s*$',text,re.M)
result={'exitCode':code,'completedTests':len(passed),'failedStatusCodes':failed,'suiteOK':bool(re.search(r'OK \(2 tests\)',text)),'apkSHA256':hashlib.sha256(apps[0].read_bytes()).hexdigest(),'environment':'Android API 35 x86_64 emulator; not Huawei hardware'}
(ev/'INSTRUMENTATION_RESULT.json').write_text(json.dumps(result,indent=2));print(text[-8000:]);print(json.dumps(result,indent=2))
assert code==0 and len(passed)==2 and not failed and result['suiteOK'],'Android tests did not pass; see device-test.log'
with tarfile.open(ev/'device-integration.tar') as t:
 names=t.getnames();assert 'integration/RESULT.json' in names and 'integration/ROI_PAIR_RESULT.json' in names,names
