"""Small replayable extension to the locked vo=gpu renderer; no floating source."""
import pathlib, sys, subprocess, hashlib, json
r = pathlib.Path(sys.argv[1])
v = r / 'video/out/gpu/video.c'
h = r / 'video/out/gpu/video.h'
def replace(s, old, new):
    assert s.count(old) == 1, f'anchor changed: {old[:70]}'
    return s.replace(old, new)
s = v.read_text()
s = replace(s, '    struct gl_video_opts opts;\n', '    struct gl_video_opts opts;\n    struct ra_tex *lvm_history;\n    struct ra_tex *lvm_frame_tex;\n    double lvm_last_pts;\n    int lvm_epoch;\n    float lvm_dt;\n    bool lvm_reset;\n')
s = replace(s, '        {"gamma-factor", OPT_FLOAT(gamma), M_RANGE(0.1, 2.0)},', '        {"lvm-params", OPT_STRING(lvm_params)},\n        {"gamma-factor", OPT_FLOAT(gamma), M_RANGE(0.1, 2.0)},')
s = replace(s, '        struct image bind_img;\n', '''        if (strcmp(bind_name, "LVM_HISTORY") == 0) {
            struct image hist = p->lvm_history ? image_wrap(p->lvm_history, PLANE_RGB, 4) : img;
            hook_prelude(p, bind_name, pass_bind(p, hist), hist);
            continue;
        }
        struct image bind_img;
''')
s = replace(s, '    gl_sc_hadd_bstr(p->sc, body);\n', '''    gl_sc_hadd_bstr(p->sc, body);
    float mode=0, ev=0, shadows=0.5f, contrast=0, saturation=1, denoise=0.3f, detail=0;
    int epoch=0, locked=0, bypass=0;
    if (p->opts.lvm_params)
        sscanf(p->opts.lvm_params, "%f %f %f %f %f %f %f %d %d %d",
               &mode, &ev, &shadows, &contrast, &saturation, &denoise, &detail, &epoch, &locked, &bypass);
    bool sdr = !pl_color_space_is_hdr(&p->image_params.color);
    if (!sdr || bypass || !isfinite(mode) || mode < 0 || mode > 4) mode=0;
    if (epoch != p->lvm_epoch) { p->lvm_epoch=epoch; p->lvm_reset=true; }
    float vals[7]={mode,ev,shadows,contrast,saturation,denoise,detail};
    const char *names[7]={"lvm_mode","lvm_ev","lvm_shadows","lvm_contrast","lvm_saturation","lvm_denoise","lvm_detail"};
    for (int n=0;n<7;n++) {
        if (!isfinite(vals[n])) vals[n]=0;
        gl_sc_uniform_dynamic(p->sc);
        gl_sc_uniform_f(p->sc,names[n],vals[n]);
    }
    gl_sc_uniform_dynamic(p->sc); gl_sc_uniform_f(p->sc,"lvm_dt",p->lvm_dt);
    gl_sc_uniform_dynamic(p->sc); gl_sc_uniform_f(p->sc,"lvm_reset",p->lvm_reset || !p->lvm_history);
    gl_sc_uniform_dynamic(p->sc); gl_sc_uniform_f(p->sc,"lvm_lock",locked ? 1.0f : 0.0f);
    float roi[4]={p->src_rect.x0/(float)p->texture_w,p->src_rect.y0/(float)p->texture_h,
                  p->src_rect.x1/(float)p->texture_w,p->src_rect.y1/(float)p->texture_h};
    gl_sc_uniform_dynamic(p->sc); gl_sc_uniform_vec2(p->sc,"lvm_roi0",roi);
    gl_sc_uniform_dynamic(p->sc); gl_sc_uniform_vec2(p->sc,"lvm_roi1",roi+2);
''')
s = replace(s, '    pass_opt_hook_point(p, "MAIN", &p->texture_offset);', '''    double pts = mpi->pts;
    double dt = pts == MP_NOPTS_VALUE ? 0.0 : pts-p->lvm_last_pts;
    p->lvm_reset = dt < 0 || dt > 0.5 || p->lvm_reset;
    p->lvm_dt = dt > 0 && dt <= 0.5 ? dt : 0;
    p->lvm_last_pts=pts;
    pass_opt_hook_point(p, "MAIN", &p->texture_offset);
    // Store exposure history after all MAIN hooks. A separate FBO avoids
    // sampling the render target being written during the next frame.
    struct image lvm_stats;
    if (saved_img_find(p,"LVM_STATS",&lvm_stats)) {
        finish_pass_tex(p,&p->lvm_frame_tex,p->texture_w,p->texture_h);
        copy_image(p,&(unsigned int){0},lvm_stats);
        finish_pass_tex(p,&p->lvm_history,1,1);
        pass_read_tex(p,p->lvm_frame_tex);
        p->lvm_reset=false;
    }
''')
s = replace(s, '    if (m_config_cache_update(p->opts_cache)) {\n', '''    void *changed;
    bool reinit=false;
    struct gl_video_opts *cached=p->opts_cache->opts;
    while (m_config_cache_get_next_changed(p->opts_cache,&changed)) {
        if (changed == &cached->lvm_params) {
            p->opts.lvm_params=cached->lvm_params;
            p->output_tex_valid=false;
        } else reinit=true;
    }
    if (reinit) {
''')
s = replace(s, '    ra_tex_free(p->ra, &p->lut_3d_texture);\n    ra_buf_free(p->ra, &p->hdr_peak_ssbo);', '    ra_tex_free(p->ra, &p->lvm_history);\n    ra_tex_free(p->ra, &p->lvm_frame_tex);\n    ra_tex_free(p->ra, &p->lut_3d_texture);\n    ra_buf_free(p->ra, &p->hdr_peak_ssbo);')
v.write_text(s)
h.write_text(replace(h.read_text(), 'struct gl_video_opts {\n', 'struct gl_video_opts {\n    char *lvm_params;\n'))
out=pathlib.Path('evidence');out.mkdir(exist_ok=True)
patch=subprocess.check_output(['git','diff','--','video/out/gpu/video.c','video/out/gpu/video.h'],cwd=r)
(out/'mpv-lumaview.patch').write_bytes(patch)
(out/'native-hook-anchors.json').write_text(json.dumps({'base':'0b7ed670f7c353dd3dd4f8ae0fc788a181a15aa6','patch_sha256':hashlib.sha256(patch).hexdigest(),'files':['video/out/gpu/video.c','video/out/gpu/video.h'],'functions':['load_shader','pass_render_frame','gl_video_update_options'],'parameter_transport':'m_config immutable option snapshot; renderer uniforms; no shader file rewrites'},indent=2))
