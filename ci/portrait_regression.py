"""Run the new portrait regression against the previously delivered APK."""
from pathlib import Path
import subprocess,os,zipfile,hashlib,json,re
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
ev=Path('release-evidence');ev.mkdir(exist_ok=True)
old=list(Path('previous-apk').rglob('*.apk'));test=list(Path('app/build/outputs/apk/androidTest/default/debug').glob('*.apk'))
assert len(old)==len(test)==1
# Instrumentation must share a signer with its target. Re-sign only the old
# comparison copy, and prove every non-signature ZIP payload is unchanged.
resigned=Path('previous-apk-comparison.apk');signer=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0/apksigner'
# AGP resolves Android preferences through ANDROID_USER_HOME / XDG_CONFIG_HOME
# before user.home. Read the actual variant's signing report, not a guessed path.
report=(ev/'gradle.log').read_text()
block=re.search(r'^Variant: defaultDebug\s*\n(.*?)(?=^Variant:|\Z)',report,re.M|re.S)
assert block,'defaultDebug signing report missing'
store=re.search(r'^Store: (.+)$',block[1],re.M)
assert store,'defaultDebug signing store missing'
key=Path(store[1].strip());assert key.is_file(),f'Gradle signing store absent: {key}'
def checked(args):
 r=subprocess.run(args,capture_output=True,text=True)
 print(r.stdout+r.stderr,flush=True)
 r.check_returncode()
 return r.stdout
checked([str(signer),'sign','--ks',str(key),'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',str(resigned),str(old[0])])
certificates=[checked([str(signer),'verify','--print-certs',str(apk)]) for apk in [resigned,test[0]]]
digests=[re.search(r'Signer #1 certificate SHA-256 digest: (\w+)',c)[1] for c in certificates]
assert digests[0]==digests[1],'comparison and instrumentation APK signers differ'
with zipfile.ZipFile(old[0]) as original,zipfile.ZipFile(resigned) as comparison:
 payload=[n for n in original.namelist() if not n.startswith('META-INF/')]
 assert payload and all(original.read(n)==comparison.read(n) for n in payload)
(ev/'previous-apk-comparison-identity.json').write_text(json.dumps({'originalApkSha256':hashlib.sha256(old[0].read_bytes()).hexdigest(),'comparisonApkSha256':hashlib.sha256(resigned.read_bytes()).hexdigest(),'allNonSignaturePayloadsUnchanged':True,'payloadCount':len(payload),'comparisonOnly':True},indent=2))
# The emulator is disposable; clear old test state and signing identities.
subprocess.run([adb,'uninstall','org.lumaview.mobile'],capture_output=True)
for apk in [resigned,test[0]]:checked([adb,'install','-r',str(apk)])
r=subprocess.run([adb,'shell','am','instrument','-w','-r','-e','class','org.lumaview.mobile.PlayerIntegrationTest#portraitPauseControlIsVisibleAndWorks','org.lumaview.mobile.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=150)
output=r.stdout.decode('utf-8','replace');(ev/'previous-apk-portrait-regression.log').write_bytes(r.stdout+r.stderr)
assert 'element is off screen' in output or 'portrait pause must remain at least 48dp wide' in output,output[-3000:]
assert 'FAILURES!!!' in output,output[-3000:]
subprocess.run([adb,'uninstall','org.lumaview.mobile'],capture_output=True)
subprocess.run([adb,'shell','wm','size','1280x800'],check=True)
subprocess.run([adb,'shell','wm','density','160'],check=True)
print('Previously delivered 0.1.1 APK fails the portrait pause control regression as expected')
