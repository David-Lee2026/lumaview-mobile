//!HOOK MAIN
//!BIND HOOKED
//!SAVE LVM_SAMPLES
//!WIDTH 64
//!HEIGHT 64
//!COMPONENTS 4
//!DESC LumaView ROI log luminance
vec2 pack16(float x){float n=floor(clamp(x,0.0,1.0)*65535.0+0.5);return vec2(floor(n/256.0),mod(n,256.0))/255.0;}
vec4 hook(){
 vec2 uv=mix(lvm_lo,lvm_hi,HOOKED_pos);
 vec3 c=max(HOOKED_tex(uv).rgb,vec3(0.0));
 float y=dot(pow(c,vec3(lvm_gamma)),vec3(0.2126,0.7152,0.0722));
 float v=clamp((log2(max(y,1.0/65536.0))+16.0)/16.0,0.0,1.0);
 return vec4(pack16(v),step(0.75,y),1.0);
}

//!HOOK MAIN
//!BIND LVM_SAMPLES
//!SAVE LVM_REDUCED
//!WIDTH 8
//!HEIGHT 8
//!COMPONENTS 4
//!DESC LumaView numerical reduction
float unpack16(vec2 c){return dot(floor(c*255.0+0.5),vec2(256.0,1.0))/65535.0;}
vec2 pack16(float x){float n=floor(clamp(x,0.0,1.0)*65535.0+0.5);return vec2(floor(n/256.0),mod(n,256.0))/255.0;}
vec4 hook(){
 vec2 origin=floor(LVM_SAMPLES_pos*8.0)*8.0;
 float s=0.0,b=0.0;
 for(int j=0;j<8;j++)for(int i=0;i<8;i++){
  vec4 p=LVM_SAMPLES_tex((origin+vec2(float(i),float(j))+0.5)/64.0);
  s+=unpack16(p.rg);b+=p.b;
 }
 return vec4(pack16(s/64.0),b/64.0,1.0);
}

//!HOOK MAIN
//!BIND LVM_REDUCED
//!BIND LVM_HISTORY
//!SAVE LVM_EV
//!WIDTH 1
//!HEIGHT 1
//!COMPONENTS 4
//!DESC LumaView GPU exposure history
float unpack16(vec2 c){return dot(floor(c*255.0+0.5),vec2(256.0,1.0))/65535.0;}
vec2 pack16(float x){float n=floor(clamp(x,0.0,1.0)*65535.0+0.5);return vec2(floor(n/256.0),mod(n,256.0))/255.0;}
vec4 hook(){
 float sum=0.0;
 for(int j=0;j<8;j++)for(int i=0;i<8;i++)sum+=unpack16(LVM_REDUCED_tex((vec2(float(i),float(j))+0.5)/8.0).rg);
 float mean=sum/64.0,logmean=mean*16.0-16.0;
 vec4 h=LVM_HISTORY_tex(vec2(0.5));float previous=unpack16(h.rg)*4.0;
 float target=clamp(min(lvm_cap,log2(0.18)-logmean)+lvm_manual,0.0,4.0);
 float ev=target;bool cut=abs(unpack16(h.ba)*16.0-16.0-logmean)>2.5;
 if(lvm_reset<0.5&&!cut){
  float amount=1.0-exp(-max(lvm_dt,0.0)/0.4);
  ev=previous+clamp((target-previous)*amount,-lvm_dt,lvm_dt);

 }
 if(lvm_lock>0.5&&lvm_reset<0.5)ev=previous;
 return vec4(pack16(ev/4.0),pack16(mean));
}

//!HOOK MAIN
//!BIND HOOKED
//!SAVE LVM_ILLUM
//!WIDTH 256
//!HEIGHT 256
//!COMPONENTS 4
//!DESC LumaView low resolution illumination
vec4 hook(){
 vec2 uv=mix(lvm_lo,lvm_hi,HOOKED_pos);
 vec3 c=HOOKED_tex(uv).rgb;
 float y=dot(c,vec3(0.2126,0.7152,0.0722));
 float s=y,w=1.0;
 for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
  vec2 q=clamp(uv+vec2(float(i),float(j))*(lvm_hi-lvm_lo)/256.0,lvm_lo+HOOKED_pt*.5,lvm_hi-HOOKED_pt*.5);
  float z=dot(HOOKED_tex(q).rgb,vec3(0.2126,0.7152,0.0722));
  float k=exp(-abs(z-y)*18.0);s+=k*z;w+=k;
 }
 return vec4(s/w,s/w,s/w,1.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND LVM_EV
//!BIND LVM_ILLUM
//!SAVE LVM_FINAL
//!WIDTH LVM_WORK_W
//!HEIGHT LVM_WORK_H
//!COMPONENTS 4
//!DESC LumaView enhanced native source region
float unpack16(vec2 c){return dot(floor(c*255.0+0.5),vec2(256.0,1.0))/65535.0;}
vec4 hook(){
 vec2 local=HOOKED_pos;
 vec2 uv=clamp(mix(lvm_lo,lvm_hi,local),lvm_lo+HOOKED_pt*.5,lvm_hi-HOOKED_pt*.5);
 vec4 source=HOOKED_tex(uv);
 if(lvm_bypass>0.5)return source;
 vec3 c=max(source.rgb,vec3(0.0));
 float lum=dot(c,vec3(.2126,.7152,.0722));
 vec3 sum=c;float weight=1.0;
 if(lvm_denoise>0.001){
  for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
   vec3 q=HOOKED_tex(clamp(uv+vec2(float(i),float(j))*HOOKED_pt,lvm_lo+HOOKED_pt*.5,lvm_hi-HOOKED_pt*.5)).rgb;
   float dy=dot(q,vec3(.2126,.7152,.0722))-lum;
   float k=exp(-dy*dy/0.0016)*(i==0&&j==0?0.0:1.0);
   sum+=k*q;weight+=k;
  }
 }
 vec3 filtered=sum/weight;
 float dark=1.0-smoothstep(.12,.65,lum);
 c=mix(c,filtered,lvm_denoise*dark);
 float illumination=LVM_ILLUM_tex(local).r;
 float ev=unpack16(LVM_EV_tex(vec2(.5)).rg)*4.0;
 ev=clamp(ev+lvm_shadows*(0.25-illumination)*1.6,0.0,4.0);
 vec3 lin=pow(max(c,vec3(0.0)),vec3(lvm_gamma));
 float gain=exp2(ev), peak=max(max(lin.r,lin.g),lin.b);
 lin=lin*gain/(1.0+peak*(gain-1.0));
 c=pow(max(lin,vec3(0.0)),vec3(1.0/lvm_gamma));
 c=(c-.5)*(1.0+lvm_contrast)+.5;
 float outlum=dot(c,vec3(.2126,.7152,.0722));
 c=mix(vec3(outlum),c,lvm_saturation);
 // Detail gain is bounded and disabled around the sensor noise floor.
 float detailGate=smoothstep(.03,.15,lum)*(1.0-smoothstep(.75,.98,outlum));
 c+=lvm_detail*detailGate*(source.rgb-filtered);
 return vec4(clamp(c,0.0,1.0),source.a);
}
