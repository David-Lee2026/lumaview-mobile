/* LumaView CPU compatibility processing. ISC license, same as lvm_ext.h. */
#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
struct lvm_cpu_controls {int mode; bool bypass,locked; double manual,shadows,contrast,saturation,denoise,detail;};
struct lvm_cpu_state {bool valid; double ev,mean,pts;};
static inline double lvm_limit(double v,double a,double b){return fmin(b,fmax(a,v));}
/* Coordinates are in a clockwise rotated output; flip precedes rotation. */
static inline void lvm_cpu_source_point(int u,int v,int w,int h,int rotation,bool flip,int *x,int *y){
 switch(rotation){case 90:*x=v;*y=h-1-u;break;case 180:*x=w-1-u;*y=h-1-v;break;case 270:*x=w-1-v;*y=u;break;default:*x=u;*y=v;break;}
 if(flip)*y=h-1-*y;
}
static int lvm_cpu_apply(uint8_t *rgba,int w,int h,int stride,const struct lvm_cpu_controls *c,struct lvm_cpu_state *s,double pts,bool reset){
 if(!rgba||!c||!s||w<=0||h<=0||w>960||h>960||stride<w*4||!isfinite(pts))return -1;
 if(c->mode==0||c->bypass)return 0;
 static const double caps[]={0,2,3,4,2};
 if(c->mode<0||c->mode>4)return -1;
 double sum=0;int count=0,bright=0;
 for(int j=0;j<16;j++)for(int i=0;i<16;i++){
  int x=(int)((i+.5)*w/16),y=(int)((j+.5)*h/16);const uint8_t *p=rgba+y*stride+x*4;
  double lum=.2126*pow(p[0]/255.,2.2)+.7152*pow(p[1]/255.,2.2)+.0722*pow(p[2]/255.,2.2);
  sum+=log2(fmax(lum,1./65536));bright+=lum>=.75;count++;
 }
 double mean=sum/count,dt=lvm_limit(pts-s->pts,0,1);
 double target=lvm_limit(lvm_limit(log2(.18)-mean,0,caps[c->mode])*(1-lvm_limit(bright*1.5/count,0,.85))+c->manual,0,4);
 if(!s->valid||reset||pts<s->pts||pts-s->pts>1||fabs(mean-s->mean)>2.5)s->ev=target;
 else if(!c->locked)s->ev+=lvm_limit((target-s->ev)*(1-exp(-dt/.4)),-dt,dt);
 s->mean=mean;s->pts=pts;s->valid=true;
 /* A bounded 256-entry LUT keeps per-pixel work free of pow/exp. */
 uint8_t lut[256];
 for(int i=0;i<256;i++){
  double x=i/255.,linear=pow(x,2.2),ev=lvm_limit(s->ev+c->shadows*(.25-x)*1.6,0,4),gain=exp2(ev);
  double out=pow(linear*gain/(1+linear*(gain-1)),1/2.2);
  out=(out-.5)*(1+c->contrast)+.5;lut[i]=(uint8_t)lrint(lvm_limit(out,0,1)*255);
 }
 for(int y=0;y<h;y++)for(int x=0;x<w;x++){
  uint8_t *p=rgba+y*stride+x*4;double r=lut[p[0]],g=lut[p[1]],b=lut[p[2]],lum=.2126*r+.7152*g+.0722*b;
  p[0]=(uint8_t)lrint(lvm_limit(lum+(r-lum)*c->saturation,0,255));
  p[1]=(uint8_t)lrint(lvm_limit(lum+(g-lum)*c->saturation,0,255));
  p[2]=(uint8_t)lrint(lvm_limit(lum+(b-lum)*c->saturation,0,255));p[3]=255;
 }
 /* Edge-gated cross filter. Reusable row storage avoids an entire extra frame. */
 if(c->mode!=4&&(c->denoise>0||c->detail>0)&&w>2&&h>2){
  uint8_t *rows=malloc((size_t)w*4*3);if(!rows)return -2;
  memcpy(rows,rgba,w*4);memcpy(rows+w*4,rgba+stride,w*4);
  for(int y=1;y<h-1;y++){
   memcpy(rows+w*8,rgba+(y+1)*stride,w*4);
   for(int x=1;x<w-1;x++)for(int k=0;k<3;k++){
    int pos=x*4+k;double center=rows[w*4+pos],sumv=center,weight=1;
    const int offsets[]={pos-4+w*4,pos+4+w*4,pos,pos+w*8};
    for(int n=0;n<4;n++){double neighbour=rows[offsets[n]],diff=fabs(neighbour-center),wt=1/(1+diff*diff/256.);sumv+=neighbour*wt;weight+=wt;}
    double smooth=sumv/weight,noise=fabs(center-smooth),out=center+(smooth-center)*c->denoise*.65;
    if(noise>1&&noise<20)out+=(center-smooth)*c->detail;
    rgba[y*stride+pos]=(uint8_t)lrint(lvm_limit(out,0,255));
   }
   memmove(rows,rows+w*4,w*8);
  }
  free(rows);
 }
 return 0;
}
