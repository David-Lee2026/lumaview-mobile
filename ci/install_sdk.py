"""Install the fixed official Android SDK/NDK in the ephemeral CI workspace."""
from pathlib import Path
import os, urllib.request, hashlib, json, zipfile, xml.etree.ElementTree as ET, subprocess, stat
root=Path(os.environ.get('RUNNER_TEMP','/tmp'))/'lvm-official-android-sdk';root.mkdir(exist_ok=True)
ev=Path('toolchain-evidence');ev.mkdir(exist_ok=True)
base='https://dl.google.com/android/repository/'
name='commandlinetools-linux-11076708_latest.zip'
def download(url,path):
    with urllib.request.urlopen(url,timeout=180) as r,open(path,'wb') as f:
        while b:=r.read(1024*1024):f.write(b)
meta=ev/'repository2-1.xml';download(base+'repository2-1.xml',meta)
tree=ET.parse(meta);found=[]
for package in tree.getroot():
    if not package.tag.endswith('remotePackage'):continue
    for complete in package.findall('.//complete'):
        if complete.findtext('url')==name:found.append(complete)
# The SDK repository uses unqualified descendants within its namespace-qualified root.
if not found:
    for complete in tree.getroot().iter():
        if complete.tag.split('}')[-1]!='complete':continue
        fields={c.tag.split('}')[-1]:c.text for c in complete}
        if fields.get('url')==name:found.append(complete)
assert found,'fixed command-line tools absent from official metadata; do not substitute latest'
fields={c.tag.split('}')[-1]:c.text for c in found[0]}
archive=root/name;download(base+name,archive)
sha1=hashlib.sha1(archive.read_bytes()).hexdigest()
assert sha1==fields['checksum'],(sha1,fields['checksum'])
assert archive.stat().st_size==int(fields['size'])
with zipfile.ZipFile(archive) as z:
    for item in z.infolist():
        target=(root/'bootstrap'/item.filename).resolve()
        assert target.is_relative_to((root/'bootstrap').resolve())
    z.extractall(root/'bootstrap')
for p in (root/'bootstrap/cmdline-tools/bin').glob('*'):p.chmod(p.stat().st_mode|stat.S_IXUSR)
manager=root/'bootstrap/cmdline-tools/bin/sdkmanager'
args=[str(manager),'--sdk_root='+str(root),'platforms;android-36','build-tools;36.0.0','ndk;30.0.16248370','platform-tools']
with (ev/'sdk-install.log').open('w') as log:
    p=subprocess.run(args,input='y\n'*1000,text=True,stdout=log,stderr=subprocess.STDOUT,timeout=900)
assert p.returncode==0,'see sdk-install.log'
ndk=root/'ndk/30.0.16248370';assert (ndk/'source.properties').exists()
assert (root/'platforms/android-36/android.jar').exists()
checks={'bootstrap_sha1':sha1,'bootstrap_sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'bootstrap_url':base+name,'sdk_root':str(root)}
for key,cmd in {'clang':[str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/clang'),'--version'],'aapt2':[str(root/'build-tools/36.0.0/aapt2'),'version'],'apksigner':[str(root/'build-tools/36.0.0/apksigner'),'version'],'packages':[str(manager),'--sdk_root='+str(root),'--list_installed']}.items():
    result=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True)
    (ev/(key+'.txt')).write_text(result.stdout)
checks['ndk_properties']=(ndk/'source.properties').read_text()
(ev/'installation.json').write_text(json.dumps(checks,indent=2))
with open(os.environ['GITHUB_ENV'],'a') as f:f.write('ANDROID_HOME='+str(root)+'\nANDROID_SDK_ROOT='+str(root)+'\nANDROID_NDK_ROOT='+str(ndk)+'\n')
with open(os.environ['GITHUB_PATH'],'a') as f:f.write(str(manager.parent)+'\n'+str(root/'platform-tools')+'\n')
print(json.dumps(checks,indent=2))
