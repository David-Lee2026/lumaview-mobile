# Compatible Android Video Implementation Plan

> For agentic workers: use superpowers:executing-plans. User already authorized implementation and branch uploads; no merge to main.

**Goal:** Deliver a new ARM64 APK with an independent software presentation path for the reported Huawei/Mali-G76 black screen, visible enhancement parameters and usable portrait playback controls.

**Architecture:** Keep mpv decoding, audio and frame scheduling. Add an Android software VO using swscale, bounded CPU ROI enhancement and ANativeWindow RGBA buffers; default it on Huawei/Honor, expose selection on all devices. A receipt must describe a successfully posted buffer, with failures explicitly logged. Keep the existing GPU path for comparison.

**Tech Stack:** Pinned mpv/FFmpeg, Android native windows, Kotlin, Gradle and existing GitHub Actions artifacts.

**Spec:** User reports and diagnostic reproduced in docs/black-screen-investigation.md.

## Global Constraints
- Only implementation/android-20261003; no main merge.
- New version 0.1.2-test / code3. No physical Huawei verification claim.
- Preserve ROI source coordinates, metadata rotation, SAR, seek/pause/speed, export and storage leases.
- Compatibility output limited to 960 pixels on its longest edge; disclose this playback limit, preserve source resolution for exports.

## Review Focus
- Portrait metadata rotation and manual 90/180/270 rotation must display the same selected source ROI.
- Surface teardown must join native VO before the holder is released.
- Window resize / large fonts must retain a visible accessible pause button.
- SDR 8/10-bit and hardware copy-back frames must reach actual system screenshots.
- Enhancement changes while paused and locked/unlocked must update actual displayed pixels.

### Task 1: Independent software presentation and enhancement
**Files:** ci/native/lvm_cpu.h, ci/native/vo_lvm_android.c, ci/patch_engine.py, PlayerSession.kt, PlayerIntegrationTest.kt.
**Interfaces:** CPU stage processes tightly owned RGBA data with stride and lvm_frame_v1 controls; VO publishes unchanged receipt ABI after ANativeWindow_unlockAndPost.
- [ ] Write host C behavioral tests for original byte preservation, brightness modes, contrast/saturation, rotation coordinates and invalid buffers; run RED before implementation.
- [ ] Implement CPU LUT and bounded VO with rotation, SAR, crop, subtitles and window screenshots.
- [ ] Run host tests, patch pinned mpv, compile ARM64/x86 and run actual window pixel instrumentation for both outputs.

### Task 2: Portrait controls, smooth timeline and useful diagnostics
**Files:** PlayerActivity.kt, PlaybackProgress.kt, CoreTest.kt, PlayerIntegrationTest.kt.
- [ ] Add portrait test at 360dp / phone density verifying a >=48dp visible pause control and paused parameter changes.
- [ ] Reproduce 0.1.1 portrait failure in CI with previous APK, then equal-width controls.
- [ ] Test small paused sample jitter separately from real backward frame stepping; implement bounded jitter handling.
- [ ] Wait for positive surface dimensions; record device identity, codec pixel format, output path, surface size and posted-frame status.

### Task 3: Deliverable and evidence
**Files:** mobile-release.yml, run_device_tests.py, verify_exports.py, investigation docs and README.
- [ ] Expand integration to software output / portrait rotation and actual pixel contrast/mode changes.
- [ ] Collect fresh review, complete both emulator API29/35 validations and ARM64 build.
- [ ] Download APK, SHA256, full logs and evidence; persist downloadable files; label Huawei physical verification NOT RUN.
