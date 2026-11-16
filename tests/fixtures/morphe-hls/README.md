# Small, real H.264/AAC HLS regression fixtures

These are **generated test patterns**, not downloaded YouTube content. Three seconds of
64×48, 10 fps H.264 Constrained Baseline video and a 440 Hz, mono 44.1 kHz AAC tone are
used by `scripts/test-runtime-repairs.py`. Total fixture size is about 156 KiB.

- `legacy.m3u8` + `.ts`: muxed MPEG-TS HLS, usable by old Android HLS implementations.
- `muxed.m3u8` + initialization MP4 / `.m4s`: a two-track fragmented-MP4 HLS stream.
- `video.m3u8`, `audio.m3u8` + corresponding files: separate H.264/AAC fMP4 renditions.

The tests feed the **actual media bytes** through production playlist selection, relative
URI/key/range rewriting, local HLS serving and classic MP4/M4A remux. With `FFMPEG` set,
all four remuxed outputs are decoded and any decoder error fails the test. This is not a
live YouTube extractor test or proof of Android 4.2 hardware-decoder/ROM behavior.

Generated with FFmpeg 9.0.2 (the `imageio-ffmpeg` host-test binary) and its built-in
`testsrc2` / `sine` sources. To recreate in this directory:

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=64x48:rate=10' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100' -t 3 \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -preset ultrafast -g 10 \
  -c:a aac -b:a 48k -f hls -hls_segment_type fmp4 -hls_time 1 \
  -hls_playlist_type vod -hls_fmp4_init_filename muxed-init.mp4 \
  -hls_segment_filename 'muxed-%02d.m4s' muxed.m3u8
ffmpeg -hide_banner -loglevel error -i muxed.m3u8 -map 0:v -c copy \
  -f hls -hls_segment_type fmp4 -hls_time 1 -hls_playlist_type vod \
  -hls_fmp4_init_filename video-init.mp4 -hls_segment_filename 'video-%02d.m4s' video.m3u8
ffmpeg -hide_banner -loglevel error -i muxed.m3u8 -map 0:a -c copy \
  -f hls -hls_segment_type fmp4 -hls_time 1 -hls_playlist_type vod \
  -hls_fmp4_init_filename audio-init.mp4 -hls_segment_filename 'audio-%02d.m4s' audio.m3u8
ffmpeg -hide_banner -loglevel error -i muxed.m3u8 -c copy -f hls \
  -hls_time 1 -hls_playlist_type vod -hls_segment_filename 'legacy-%02d.ts' legacy.m3u8
```

Run from the repository root after bootstrapping the JDK:

```sh
make test-runtime
# Optional, stronger host decoder check:
FFMPEG=/path/to/ffmpeg make test-runtime
```
