//!HOOK MAIN
//!BIND HOOKED
//!SAVE LVM_GRID
//!WIDTH 64
//!HEIGHT 64
//!COMPONENTS 4
//!DESC LumaView visible ROI log statistics
vec4 hook() {
    vec2 extent = max(lvm_roi1-lvm_roi0, vec2(0.0001));
    vec2 count = clamp(64.0*extent*HOOKED_size/max(extent.x*HOOKED_size.x,extent.y*HOOKED_size.y),vec2(16.0),vec2(64.0));
    vec2 cell=floor(HOOKED_pos*vec2(64.0));
    if(any(greaterThanEqual(cell,count))) return vec4(0.0);
    vec2 pos=mix(lvm_roi0,lvm_roi1,(cell+0.5)/count);
    vec3 rgb=max(HOOKED_tex(pos).rgb,vec3(0.0));
    float y=dot(pow(rgb,vec3(2.2)),vec3(0.2126,0.7152,0.0722));
    return vec4((log2(max(y,0.000015258789))/16.0)+1.0,step(0.75,max(rgb.r,max(rgb.g,rgb.b))),1.0,1.0);
}

//!HOOK MAIN
//!BIND LVM_GRID
//!BIND LVM_HISTORY
//!SAVE LVM_STATS
//!WIDTH 1
//!HEIGHT 1
//!COMPONENTS 4
//!DESC LumaView ROI reduction and time-based exposure
vec4 hook() {
    vec3 sums=vec3(0.0);
    for(int y=0;y<64;y++) for(int x=0;x<64;x++) sums+=LVM_GRID_tex((vec2(float(x),float(y))+0.5)/64.0).rgb;
    float mean=sums.x/max(sums.z,1.0)*16.0-16.0;
    float bright=sums.y/max(sums.z,1.0);
    float cap=lvm_mode<1.5?2.0:(lvm_mode<2.5?3.0:(lvm_mode<3.5?4.0:2.0));
    float target=clamp(log2(0.18)-mean+lvm_ev,0.0,cap);
    target*=1.0-clamp(bright*1.5,0.0,0.85);
    float last=LVM_HISTORY_tex(vec2(0.5)).r*4.0;
    float alpha=1.0-exp(-max(lvm_dt,0.0)/0.4);
    bool cut=abs(mean-(LVM_HISTORY_tex(vec2(0.5)).g*16.0-16.0))>3.0;
    float ev=lvm_reset>0.5||cut?target:last+clamp(alpha*(target-last),-lvm_dt,lvm_dt);
    if(lvm_lock>0.5&&lvm_reset<0.5)ev=last;
    return vec4(clamp(ev,0.0,4.0)/4.0,(mean+16.0)/16.0,bright,sums.z/4096.0);
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND LVM_STATS
//!DESC LumaView edge-aware low-light pixels
vec4 hook() {
    vec4 original=HOOKED_tex(HOOKED_pos);
    if(lvm_mode<0.5)return original;
    vec3 c=max(original.rgb,vec3(0.0));
    float y=dot(c,vec3(0.2126,0.7152,0.0722));
    vec3 sum=c;float weights=1.0;
    if(lvm_mode<3.5&&lvm_denoise>0.0) {
        for(int j=-1;j<=1;j++)for(int i=-1;i<=1;i++){
            vec3 n=HOOKED_texOff(vec2(float(i),float(j))).rgb;
            float w=exp(-abs(dot(n,vec3(0.2126,0.7152,0.0722))-y)*50.0);
            sum+=n*w;weights+=w;
        }
        c=mix(c,sum/weights,clamp(lvm_denoise,0.0,1.0)*(1.0-smoothstep(0.1,0.6,y)));
    }
    float ev=LVM_STATS_tex(vec2(0.5)).r*4.0;
    vec3 linear=pow(max(c,vec3(0.0)),vec3(2.2));
    float local=1.0+clamp(lvm_shadows,0.0,1.0)*(1.0-smoothstep(0.0,0.45,y))*0.35;
    float gain=exp2(ev)*local;
    linear=linear*gain/(1.0+linear*(gain-1.0));
    c=pow(clamp(linear,vec3(0.0),vec3(1.0)),vec3(1.0/2.2));
    float lum=dot(c,vec3(0.2126,0.7152,0.0722));
    c=mix(vec3(lum),c,clamp(lvm_saturation,0.0,1.5));
    c=(c-0.5)*(1.0+clamp(lvm_contrast,-0.25,0.25))+0.5;
    if(lvm_detail>0.0&&y>0.05)c+=(original.rgb-sum/weights)*clamp(lvm_detail,0.0,0.3);
    return vec4(clamp(c,vec3(0.0),vec3(1.0)),original.a);
}
