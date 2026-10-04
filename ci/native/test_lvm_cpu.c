#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "lvm_cpu.h"
int main(void) {
 uint8_t image[4*16*16], original[sizeof(image)];
 for(int i=0;i<256;i++){image[4*i]=40;image[4*i+1]=50;image[4*i+2]=60;image[4*i+3]=255;}
 memcpy(original,image,sizeof(image));
 struct lvm_cpu_state state={0};
 struct lvm_cpu_controls c={.mode=0,.saturation=1,.shadows=.6,.denoise=.4,.detail=.08};
 assert(lvm_cpu_apply(image,16,16,64,&c,&state,0,1)==0);
 assert(!memcmp(image,original,sizeof(image)));
 c.mode=1;assert(lvm_cpu_apply(image,16,16,64,&c,&state,0,1)==0);
 assert(image[0]>original[0]+10 && image[3]==255);
 double balanced=image[0];memcpy(image,original,sizeof(image));c.mode=3;
 assert(lvm_cpu_apply(image,16,16,64,&c,&state,0,1)==0);assert(image[0]>balanced);
 memcpy(image,original,sizeof(image));c.bypass=true;
 assert(lvm_cpu_apply(image,16,16,64,&c,&state,0,1)==0);assert(!memcmp(image,original,sizeof(image)));
 c.bypass=false;c.saturation=0;memcpy(image,original,sizeof(image));
 assert(lvm_cpu_apply(image,16,16,64,&c,&state,0,1)==0);assert(image[0]==image[1]&&image[1]==image[2]);
 assert(lvm_cpu_apply(image,16,16,10,&c,&state,0,1)<0);
 assert(lvm_cpu_apply(NULL,16,16,64,&c,&state,0,1)<0);
 int x,y;lvm_cpu_source_point(0,0,3,2,90,false,&x,&y);assert(x==0&&y==1);
 lvm_cpu_source_point(1,2,3,2,90,false,&x,&y);assert(x==2&&y==0);
 lvm_cpu_source_point(0,0,3,2,180,false,&x,&y);assert(x==2&&y==1);
 lvm_cpu_source_point(0,0,3,2,270,false,&x,&y);assert(x==2&&y==0);
 puts("CPU original/modes/saturation/invalid-buffer/rotation tests passed");
}
