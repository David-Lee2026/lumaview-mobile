"""Prepare upstream native build inputs; this is a build tool, not the player."""
from pathlib import Path
import subprocess, json, hashlib, os, urllib.request, re, shutil
ROOT=Path.cwd(); E=ROOT/'native-evidence'; E.mkdir(exist_ok=True)
SRC=ROOT/'upstream'
def run(*args,cwd=None):
    subprocess.run(list(args),cwd=cwd,check=True)
def out(*args,cwd=None):
    return subprocess.check_output(list(args),cwd=cwd,text=True).strip()
rev='fdf74f6830c47dbaa8a22ac79726e8303f1db5af'
run('git','init',str(SRC));run('git','remote','add','origin','https://github.com/mpv-android/mpv-android.git',cwd=SRC)
run('git','fetch','--depth=1','origin',rev,cwd=SRC);run('git','checkout','--detach','FETCH_HEAD',cwd=SRC)
assert out('git','rev-parse','HEAD',cwd=SRC)==rev
B=SRC/'buildscripts';(B/'sdk').mkdir(exist_ok=True)
os.symlink(os.environ['ANDROID_HOME'],B/'sdk/android-sdk-linux')
os.symlink(Path(os.environ['ANDROID_HOME'])/'ndk/30.0.16248370',B/'sdk/android-ndk-r30')
# Upstream downloader uses versioned archives plus a few git checkouts. Before
# any build, replace each floating checkout by the release's exact git object.
run('bash','include/download-deps.sh',cwd=B)
req=urllib.request.Request('https://api.github.com/repos/mpv-android/mpv-android/releases/tags/2026-09-17',headers={'User-Agent':'LumaView-build'})
release=json.load(urllib.request.urlopen(req,timeout=90))
(E/'release.json').write_text(json.dumps(release,indent=2))
revisions={'dav1d':'362bc11','ffmpeg':'094a2f8a2a5e7fa64736e067de224ce28fdf5979','libass':'b2fe9d8','libplacebo':'3330a51','mpv':'0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6'}
manifest={}
for name in sorted(p.name for p in (B/'deps').iterdir() if p.is_dir()):
    p=B/'deps'/name
    if (p/'.git').exists():
        if name in revisions:run('git','checkout','--detach',revisions[name],cwd=p)
        if (p/'.gitmodules').exists():run('git','submodule','update','--init','--recursive',cwd=p)
        manifest[name]={'commit':out('git','rev-parse','HEAD',cwd=p),'submodules':out('git','submodule','status','--recursive',cwd=p)}
    else:manifest[name]={'versioned_archive':True}
    h=hashlib.sha256(); count=0
    for f in sorted(p.rglob('*')):
        if not f.is_file() or '.git' in f.relative_to(p).parts:continue
        r=f.relative_to(p).as_posix();h.update(r.encode());h.update(b'\0');h.update(hashlib.sha256(f.read_bytes()).digest());count+=1
    manifest[name]['tree_content_sha256']=h.hexdigest();manifest[name]['file_count']=count
(E/'dependencies-lock.json').write_text(json.dumps(manifest,indent=2))
# Download helper is a build-only script. Record its exact full commit too.
helper=ROOT/'gas-preprocessor';run('git','clone','--depth=1','https://github.com/FFmpeg/gas-preprocessor.git',str(helper))
(B/'sdk/bin').mkdir(exist_ok=True);shutil.copy2(helper/'gas-preprocessor.pl',B/'sdk/bin/gas-preprocessor.pl');os.chmod(B/'sdk/bin/gas-preprocessor.pl',0o755)
(E/'gas-preprocessor-commit.txt').write_text(out('git','rev-parse','HEAD',cwd=helper))
# Retain compact code and exact dependency metadata separately from build outputs.
run('tar','czf',str(E/'upstream-application-source.tar.gz'),'--exclude=.git','--exclude=buildscripts/deps','--exclude=buildscripts/sdk','--exclude=*.ttf','--exclude=*.otf','--exclude=*.ttc','--exclude=*.woff','--exclude=*.woff2','.',cwd=SRC)
print(json.dumps(manifest,indent=2))
