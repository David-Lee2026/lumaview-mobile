#pragma once
#include <stdint.h>
struct lvm_frame_counter { uint64_t last_id, count; };
static inline uint64_t lvm_count_frame(struct lvm_frame_counter *c, uint64_t id, int valid) {
    if (valid && id && id != c->last_id) { c->last_id=id; c->count++; }
    return c->count;
}
