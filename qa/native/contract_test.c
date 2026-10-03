#include <assert.h>
#include <math.h>
#include "mpv/lvm_ext.h"
int main(void) {
 struct lvm_frame_v1 f={.size=sizeof(f),.version=1,.request_id=1,.mode=1};
 assert(lvm_frame_valid(&f));f.manual[0]=NAN;assert(!lvm_frame_valid(&f));f.manual[0]=0;
 f.version=2;assert(!lvm_frame_valid(&f));f.version=1;f.reserved[2]=1;assert(!lvm_frame_valid(&f));
 return 0;
}
