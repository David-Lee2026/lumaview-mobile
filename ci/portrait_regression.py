"""Run the new portrait regression against the previously delivered APK."""
from pathlib import Path
import subprocess,os
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb')
ev=Path('release-evidence');ev.mkdir(exist_ok=True)
old=list(Path('previous-apk').rglob('*.apk'));test=list(Path('app/build/outputs/apk/androidTest/default/debug').glob('*.apk'))
assert len(old)==len(test)==1
# The emulator is disposable; clear old test state and signing identities.
subprocess.run([adb,'uninstall','org.lumaview.mobile'],capture_output=True)
for apk in [old[0],test[0]]:subprocess.run([adb,'install','-r',str(apk)],check=True,capture_output=True)
r=subprocess.run([adb,'shell','am','instrument','-w','-r','-e','class','org.lumaview.mobile.PlayerIntegrationTest#portraitPauseControlIsVisibleAndWorks','org.lumaview.mobile.test/androidx.test.runner.AndroidJUnitRunner'],capture_output=True,timeout=150)
output=r.stdout.decode('utf-8','replace');(ev/'previous-apk-portrait-regression.log').write_bytes(r.stdout+r.stderr)
assert 'element is off screen' in output or 'portrait pause must remain at least 48dp wide' in output,output[-3000:]
assert 'FAILURES!!!' in output,output[-3000:]
subprocess.run([adb,'uninstall','org.lumaview.mobile'],capture_output=True)
subprocess.run([adb,'shell','wm','size','1280x800'],check=True)
subprocess.run([adb,'shell','wm','density','160'],check=True)
print('Previously delivered 0.1.1 APK fails the portrait pause control regression as expected')
