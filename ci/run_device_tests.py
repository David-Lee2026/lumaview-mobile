"""Run installed Android tests, then retain private evidence before uninstalling.

This intentionally does not use Gradle connectedAndroidTest: its cleanup can remove
application data before the host collects screenshots and exported media.
"""
from pathlib import Path
import hashlib, json, re, subprocess, tarfile

EXPECTED = {
    'org.lumaview.mobile.PlayerIntegrationTest#realTouchRenderingAndExports',
    'org.lumaview.mobile.PlayerIntegrationTest#roiPixelsIgnoreOutsideAndRespondInsideWithNewIntents',
    'org.lumaview.mobile.TouchTerminationTest#cancelledSeekHasExactlyOneTerminalEvent',
    'org.lumaview.mobile.TouchTerminationTest#secondPointerTerminatesThePreviousSeek',
    'org.lumaview.mobile.TouchTerminationTest#cancelledClipDragCommitsOneTerminalPreview',
}

def parse_instrumentation(text: str, expected: set[str]) -> list[dict]:
    if not re.search(r'OK \(\d+ tests?\)', text) or 'INSTRUMENTATION_CODE: -1' not in text:
        raise ValueError('Missing successful instrumentation termination')
    if any(s in text for s in ('FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed')):
        raise ValueError('Instrumentation reports failure')
    fields = {}; results = []
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, sep, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if sep: fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.rsplit(':',1)[1].strip())
            if code != 1:
                if code != 0: raise ValueError(f'Failed or skipped case: {fields}, code {code}')
                name = fields.get('class','')+'#'+fields.get('test','')
                results.append({'test':name,'statusCode':code})
            fields = {}
    names = [r['test'] for r in results]
    if len(names) != len(expected) or set(names) != expected:
        raise ValueError(f'Expected {sorted(expected)}, observed {names}')
    return results

def main():
    ev=Path('release-evidence');ev.mkdir(exist_ok=True)
    def adb(*args, timeout=45):
        return subprocess.run(['adb',*map(str,args)],capture_output=True,timeout=timeout)
    devices=adb('devices').stdout.decode()
    serials=re.findall(r'^([^\s]+)\s+device$', devices,re.M)
    if len(serials)!=1: raise RuntimeError('Exactly one CI test emulator is required')
    if adb('shell','getprop','ro.kernel.qemu').stdout.strip()!=b'1':
        raise RuntimeError('This CI runner must not install onto an unselected physical device')
    def checked(*args):
        p=adb(*args)
        if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
        return p.stdout.decode(errors='replace')
    apks=list(Path('release-out').glob('*.apk'))
    test_apks=list(Path('app/build/outputs/apk/androidTest').rglob('*.apk'))
    if len(apks)!=1 or len(test_apks)!=1: raise RuntimeError(f'Ambiguous APK selection: {apks}, {test_apks}')
    for apk in [*apks,*test_apks]:
        result=checked('install','-r','-t',apk)
        if 'Success' not in result: raise RuntimeError(result)
    checked('logcat','-c')
    try:
        p=adb('shell','am','instrument','-w','-r','org.lumaview.mobile.test/androidx.test.runner.AndroidJUnitRunner',timeout=360)
        text=p.stdout.decode(errors='replace')+'\n'+p.stderr.decode(errors='replace')
        (ev/'instrumentation.txt').write_text(text)
        (ev/'instrumentation-exit.txt').write_text(str(p.returncode))
    finally:
        (ev/'logcat.txt').write_bytes(adb('logcat','-d').stdout)
        # No uninstall or clear-data occurs before this copy.
        evidence=adb('exec-out','run-as','org.lumaview.mobile','tar','-C','files','-cf','-','integration')
        (ev/'device-integration.tar').write_bytes(evidence.stdout)
        (ev/'evidence-copy-stderr.txt').write_bytes(evidence.stderr)
        properties={k:checked('shell','getprop',k).strip() for k in ['ro.build.version.sdk','ro.product.cpu.abi','ro.kernel.qemu','ro.product.model']}
        (ev/'device-environment.json').write_text(json.dumps(properties,indent=2))
    if p.returncode: raise RuntimeError(text)
    cases=parse_instrumentation(text,EXPECTED)
    dest=ev/'device';dest.mkdir(exist_ok=True)
    with tarfile.open(ev/'device-integration.tar') as archive:
        for item in archive.getmembers():
            if not (dest/item.name).resolve().is_relative_to(dest.resolve()) or item.issym() or item.islnk():
                raise ValueError('Unsafe artifact path')
        archive.extractall(dest,filter='data')
    for name in ['RESULT.json','ROI_PAIR_RESULT.json','copy.mp4','exact.mp4','roi-original.png','roi-enhanced.png']:
        if not (dest/'integration'/name).is_file(): raise RuntimeError('Missing app evidence: '+name)
    summary={'status':'PASS','tests':cases,'apkSha256':hashlib.sha256(apks[0].read_bytes()).hexdigest(),'physicalDevice':False,'environment':properties,'evidenceRetainedBeforeUninstall':True}
    (ev/'ANDROID_RESULT.json').write_text(json.dumps(summary,indent=2));print(json.dumps(summary,indent=2))

if __name__=='__main__':main()
