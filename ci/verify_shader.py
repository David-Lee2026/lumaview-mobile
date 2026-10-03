"""Compile every mpv user-shader pass as GLES 3.0 before native rebuilding."""
from pathlib import Path
import re,subprocess,tempfile
results=[]
with tempfile.TemporaryDirectory() as temp:
 for name in ['mobile','compatible']:
  source=Path('shaders/lvm/'+name+'.glsl').read_text()
  assert Path('app/src/main/assets/lvm/'+name+'.glsl').read_text()==source
  for i,block in enumerate(source.split('//!HOOK MAIN')[1:]):
   binds=re.findall(r'^//!BIND (\w+)',block,re.MULTILINE)
   preamble=['#version 300 es','precision highp float;','in vec2 uv;','out vec4 result;','uniform vec2 lvm_lo,lvm_hi;']
   for uniform in ['cap','manual','shadows','contrast','saturation','denoise','detail','bypass','lock','reset','dt','gamma']:preamble.append('uniform float lvm_'+uniform+';')
   for bind in binds:
    preamble.extend([f'uniform sampler2D {bind}_raw;',f'uniform vec2 {bind}_pt;',f'#define {bind}_pos uv',f'#define {bind}_tex(p) texture({bind}_raw,(p))'])
   code='\n'.join(preamble)+'\n'+re.sub(r'^//!.*$','',block,flags=re.MULTILINE)+'\nvoid main(){result=hook();}\n'
   path=Path(temp)/f'{name}-pass-{i}.frag';path.write_text(code)
   log=subprocess.check_output(['glslangValidator','-S','frag',str(path)],stderr=subprocess.STDOUT,text=True)
   results.append({'shader':name,'pass':i,'glesVersion':'300 es','compiled':True})
import json
Path('release-evidence').mkdir(exist_ok=True)
Path('release-evidence/shader-syntax.json').write_text(json.dumps(results,indent=2));print(json.dumps(results))
