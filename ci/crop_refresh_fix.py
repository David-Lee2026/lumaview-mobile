"""Keep paused crop refresh on the core-owned option snapshot.

The video-output thread updates vo->opts asynchronously. The core's refresh seek
can enqueue a frame before that cache sees a just-written crop. Reading the core
MPOpts prevents a stale full-frame crop from being submitted while paused.
"""
from pathlib import Path
import sys

def apply(root: Path):
    changes={
        'player/video.c':('struct m_geometry *gm = &vo->opts->video_crop;',
                          'struct m_geometry *gm = &mpctx->opts->vo->video_crop;'),
        'player/command.c':('struct m_geometry *gm = &mpctx->video_out->opts->video_crop;',
                            'struct m_geometry *gm = &mpctx->opts->vo->video_crop;'),
    }
    for relative,(old,new) in changes.items():
        p=root/relative;s=p.read_text()
        if new in s and old not in s:continue
        if s.count(old)!=1:raise ValueError('Unexpected pinned mpv crop anchor: '+relative)
        p.write_text(s.replace(old,new,1))
    print('Applied core-owned crop refresh fix:',root)

if __name__=='__main__':apply(Path(sys.argv[1] if len(sys.argv)>1 else 'buildscripts/deps/mpv'))
