"""Reuse run 37127934301's fixed native prefixes, rebuild only patched mpv/JNI."""
from pathlib import Path
import subprocess, os, shutil, json, hashlib
root=Path.cwd(); ev=root/'evidence';ev.mkdir(exist_ok=True)
def run(*args,cwd=root):
    print('+',' '.join(map(str,args)),flush=True)
    subprocess.run(list(map(str,args)),cwd=cwd,check=True)
up=root/'upstream'
run('git','init',up);run('git','remote','add','origin','https://github.com/mpv-android/mpv-android.git',cwd=up)
run('git','fetch','--depth=1','origin','fdf74f6830c47dbaa8a22ac79726e8303f1db5af',cwd=up);run('git','checkout','--detach','FETCH_HEAD',cwd=up)
b=up/'buildscripts';(b/'sdk').mkdir(exist_ok=True)
os.symlink(os.environ['ANDROID_HOME'],b/'sdk/android-sdk-linux')
os.symlink(os.environ['ANDROID_NDK_ROOT'],b/'sdk/android-ndk-r30')
for arch in ['arm64','x86_64']:
    archive=root/f'native-inputs/{arch}/native-evidence/prefix-{arch}.tar.gz'
    assert archive.is_file(),archive
    run('tar','xzf',archive,'-C',b)
    shutil.copy2(root/f'native-inputs/{arch}/native-evidence/dependencies-lock.json',ev/f'dependencies-{arch}.json')
mpv=b/'deps/mpv';mpv.mkdir(parents=True)
run('git','init',mpv);run('git','remote','add','origin','https://github.com/mpv-player/mpv.git',cwd=mpv)
run('git','fetch','--depth=1','origin','0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6',cwd=mpv);run('git','checkout','--detach','FETCH_HEAD',cwd=mpv)
run('python3','ci/apply_native_patch.py',mpv)
for arch in ['arm64','x86_64']:
    run('bash','buildall.sh','-n','--arch',arch,'mpv',cwd=b)
env=os.environ.copy();env.update(PREFIX64=str(b/'prefix/arm64'),PREFIX_X64=str(b/'prefix/x86_64'))
subprocess.run([str(Path(env['ANDROID_NDK_ROOT'])/'ndk-build'),'-C',str(root/'app/src/main'),'-j2'],env=env,check=True)
fixture=root/'app/src/androidTest/assets/fixture.mp4';fixture.parent.mkdir(parents=True,exist_ok=True)
run('ffmpeg','-hide_banner','-loglevel','error','-y','-f','lavfi','-i','testsrc2=size=640x360:rate=30','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','8','-vf','eq=brightness=-0.30:contrast=0.40','-c:v','libx264','-pix_fmt','yuv420p','-g','30','-bf','2','-c:a','aac',fixture)
run('./gradlew','testDefaultDebugUnitTest','assembleDefaultDebug','assembleDefaultDebugAndroidTest','--stacktrace')
release=root/'release';release.mkdir(exist_ok=True)
tool=Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0'
for abi in ['arm64-v8a','x86_64']:
    src=root/f'app/build/outputs/apk/default/debug/app-default-{abi}-debug.apk'
    dest=release/f'LumaView_Mobile_0.1.0-test_{abi}.apk';shutil.copy2(src,dest)
    with (ev/f'apksigner-{abi}.txt').open('w') as f:subprocess.run([str(tool/'apksigner'),'verify','--verbose','--print-certs',str(dest)],stdout=f,stderr=subprocess.STDOUT,check=True)
    with (ev/f'zipalign-{abi}.txt').open('w') as f:subprocess.run([str(tool/'zipalign'),'-c','-P','16','-v','4',str(dest)],stdout=f,stderr=subprocess.STDOUT,check=True)
    with (ev/f'badging-{abi}.txt').open('w') as f:subprocess.run([str(tool/'aapt'),'dump','badging',str(dest)],stdout=f,check=True)
files=list(release.glob('*.apk'))
(release/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in files))
(ev/'build-manifest.json').write_text(json.dumps({'sourceCommit':os.environ['GITHUB_SHA'],'upstreamApp':'fdf74f6830c47dbaa8a22ac79726e8303f1db5af','upstreamMpv':'0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6','nativeRun':37127934301,'nativeArtifacts':{'arm64':11274584701,'x86_64':11274774400},'apkHashes':{f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in files},'signature':'ephemeral Android debug certificate; not a stable release signing key','physicalHuawei':{'status':'BLOCKED','reason':'No attached Mate 20 X or Huawei physical device'}},indent=2))
print('APK BUILD COMPLETE',flush=True)
