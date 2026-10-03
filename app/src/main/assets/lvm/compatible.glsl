//!HOOK MAIN
//!BIND HOOKED
//!SAVE LVM_SAMPLES
//!WIDTH 8
//!HEIGHT 8
//!COMPONENTS 4
//!DESC LumaView compatible ROI luminance
vec2 pack16(float x){float n=floor(clamp(x,0.0,1.0)*65535.0+0.5);return vec2(floor(n/256.0),mod(n,256.0))/255.0;}
vec4 hook(){
 vec2 uv=clamp(mix(lvm_lo,lvm_hi,HOOKED_pos),lvm_lo+HOOKED_pt*.5,lvm_hi-HOOKED_pt*.5);
 vec3 c=max(HOOKED_tex(uv).rgb,vec3(0.0));
 float y=dot(pow(c,vec3(lvm_gamma)),vec3(.2126,.7152,.0722));
 return vec4(pack16(clamp((log2(max(y,1.0/65536.0))+16.0)/16.0,0.0,1.0)),0.0,1.0);
}

//!HOOK MAIN
//!BIND LVM_SAMPLES
//!BIND LVM_HISTORY
//!SAVE LVM_EV
//!WIDTH 1
//!HEIGHT 1
//!COMPONENTS 4
//!DESC LumaView compatible exposure history
float unpack16(vec2 c){return dot(floor(c*255.0+.5),vec2(256.0,1.0))/65535.0;}
vec2 pack16(float x){float n=floor(clamp(x,0.0,1.0)*65535.0+.5);return vec2(floor(n/256.0),mod(n,256.0))/255.0;}
vec4 hook(){
 float sum=0.0;
 for(int j=0;j<8;j++)for(int i=0;i<8;i++)sum+=unpack16(LVM_SAMPLES_tex((vec2(float(i),float(j))+.5)/8.0).rg);
 float mean=sum/64.0,logmean=mean*16.0-16.0;
 vec4 history=LVM_HISTORY_tex(vec2(.5));float previous=unpack16(history.rg)*4.0;
 float target=clamp(min(lvm_cap,log2(.18)-logmean)+lvm_manual,0.0,4.0),ev=target;
 bool cut=abs(unpack16(history.ba)*16.0-16.0-logmean)>2.5;
 if(lvm_reset<.5&&!cut)ev=previous+clamp((target-previous)*(1.0-exp(-max(lvm_dt,0.0)/.4)),-lvm_dt,lvm_dt);
 if(lvm_lock>.5&&lvm_reset<.5)ev=previous;
 return vec4(pack16(ev/4.0),pack16(mean));
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND LVM_EV
//!SAVE LVM_FINAL
//!WIDTH LVM_WORK_W
//!HEIGHT LVM_WORK_H
//!COMPONENTS 4
//!DESC LumaView compatible adaptive enhancement
float unpack16(vec2 c){return dot(floor(c*255.0+.5),vec2(256.0,1.0))/65535.0;}
vec4 hook(){
 vec2 uv=clamp(mix(lvm_lo,lvm_hi,HOOKED_pos),lvm_lo+HOOKED_pt*.5,lvm_hi-HOOKED_pt*.5);
 vec4 source=HOOKED_tex(uv);vec3 c=max(source.rgb,vec3(0.0));
 float lum=dot(c,vec3(.2126,.7152,.0722));
 float ev=clamp(unpack16(LVM_EV_tex(vec2(.5)).rg)*4.0+lvm_shadows*(.25-lum)*1.6,0.0,4.0);
 vec3 linear=pow(c,vec3(lvm_gamma));float gain=exp2(ev),peak=max(max(linear.r,linear.g),linear.b);
 c=pow(max(linear*gain/(1.0+peak*(gain-1.0)),vec3(0.0)),vec3(1.0/lvm_gamma));
 c=(c-.5)*(1.0+lvm_contrast)+.5;c=mix(vec3(dot(c,vec3(.2126,.7152,.0722))),c,lvm_saturation);
 return vec4(clamp(c,0.0,1.0),source.a);
}
