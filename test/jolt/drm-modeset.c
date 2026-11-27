/* drm-modeset.c - minimal DRM modeset client (no libdrm). Opens a DRM
 * card, finds the first connected connector, sets a mode with a dumb
 * framebuffer, flushes a frame, then clears and exits. Proves virtio-gpu
 * scanout end-to-end inside a krun VM. Static-musl build for the guest.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdint.h>
#include <sys/mman.h>
#include <drm/drm.h>
#include <drm/drm_mode.h>

#define DRM_FORMAT_XRGB8888 0x34325258
#define DRM_MODE_CONNECTED 1
extern int ioctl(int, unsigned long, ...);

static int fd;

static void die(const char *m) {
  fprintf(stderr, "%s: %s\n", m, strerror(errno));
  exit(1);
}

int main(int argc, char **argv) {
  const char *card = argc > 1 ? argv[1] : "/dev/dri/card0";
  uint32_t want_w = argc > 2 ? (uint32_t)atoi(argv[2]) : 640;
  uint32_t want_h = argc > 3 ? (uint32_t)atoi(argv[3]) : 480;

  fd = open(card, O_RDWR | O_CLOEXEC);
  if (fd < 0) die("open");

  struct drm_mode_card_res res;
  memset(&res, 0, sizeof res);
  if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) < 0) die("GETRESOURCES");
  fprintf(stderr, "resources: %u crtcs %u connectors %u encoders\n",
          res.count_crtcs, res.count_connectors, res.count_encoders);
  if (!res.count_connectors || !res.count_crtcs) {
    fprintf(stderr, "no display\n");
    return 2;
  }

  uint32_t conn_ids[res.count_connectors];
  uint32_t crtc_ids[res.count_crtcs];
  res.connector_id_ptr = (uint64_t)(uintptr_t)conn_ids;
  res.crtc_id_ptr = (uint64_t)(uintptr_t)crtc_ids;
  res.count_fbs = 0;
  res.count_encoders = 0;
  res.fb_id_ptr = 0;
  res.encoder_id_ptr = 0;
  if (ioctl(fd, DRM_IOCTL_MODE_GETRESOURCES, &res) < 0) die("GETRESOURCES2");

  uint32_t chosen_conn = 0, chosen_crtc = 0;
  struct drm_mode_modeinfo mode;
  int found = 0;
  for (uint32_t i = 0; i < res.count_connectors && !found; i++) {
    struct drm_mode_get_connector conn;
    memset(&conn, 0, sizeof conn);
    conn.connector_id = conn_ids[i];
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &conn) < 0) continue;
    fprintf(stderr, "connector %u: type %u connection %u modes %u encoders %u\n",
            conn.connector_id, conn.connector_type, conn.connection,
            conn.count_modes, conn.count_encoders);
    if (conn.connection != DRM_MODE_CONNECTED || !conn.count_modes ||
        !conn.count_encoders)
      continue;
    struct drm_mode_modeinfo modes[conn.count_modes];
    uint32_t encoders[conn.count_encoders];
    conn.modes_ptr = (uint64_t)(uintptr_t)modes;
    conn.encoders_ptr = (uint64_t)(uintptr_t)encoders;
    conn.count_props = 0;
    conn.props_ptr = 0;
    conn.prop_values_ptr = 0;
    if (ioctl(fd, DRM_IOCTL_MODE_GETCONNECTOR, &conn) < 0) continue;
    int picked = -1;
    for (uint32_t m = 0; m < conn.count_modes; m++) {
      if (modes[m].hdisplay == want_w && modes[m].vdisplay == want_h) {
        picked = (int)m;
        break;
      }
    }
    if (picked < 0) picked = 0;
    mode = modes[picked];
    chosen_conn = conn.connector_id;
    struct drm_mode_get_encoder enc;
    memset(&enc, 0, sizeof enc);
    enc.encoder_id = encoders[0];
    if (ioctl(fd, DRM_IOCTL_MODE_GETENCODER, &enc) < 0) continue;
    uint32_t crtc = enc.crtc_id;
    if (crtc == 0) {
      /* encoder not yet attached: pick the first CRTC it can drive */
      for (uint32_t c = 0; c < res.count_crtcs; c++) {
        if (enc.possible_crtcs & (1u << c)) {
          crtc = crtc_ids[c];
          break;
        }
      }
    }
    if (crtc == 0) continue;
    chosen_crtc = crtc;
    found = 1;
    fprintf(stderr, "picked mode %ux%u conn %u crtc %u\n",
            mode.hdisplay, mode.vdisplay, chosen_conn, chosen_crtc);
  }
  if (!found) {
    fprintf(stderr, "no connected connector\n");
    return 2;
  }

  struct drm_mode_create_dumb cd;
  memset(&cd, 0, sizeof cd);
  cd.width = mode.hdisplay;
  cd.height = mode.vdisplay;
  cd.bpp = 32;
  if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &cd) < 0) die("CREATE_DUMB");
  fprintf(stderr, "dumb handle %u pitch %u size %llu\n", cd.handle, cd.pitch,
          (unsigned long long)cd.size);

  struct drm_mode_fb_cmd2 fb;
  memset(&fb, 0, sizeof fb);
  fb.width = mode.hdisplay;
  fb.height = mode.vdisplay;
  fb.pixel_format = DRM_FORMAT_XRGB8888;
  fb.handles[0] = cd.handle;
  fb.pitches[0] = cd.pitch;
  fb.offsets[0] = 0;
  if (ioctl(fd, DRM_IOCTL_MODE_ADDFB2, &fb) < 0) die("ADDFB2");
  fprintf(stderr, "fb id %u\n", fb.fb_id);

  struct drm_mode_map_dumb md;
  memset(&md, 0, sizeof md);
  md.handle = cd.handle;
  if (ioctl(fd, DRM_IOCTL_MODE_MAP_DUMB, &md) < 0) die("MAP_DUMB");
  uint32_t *fbuf = mmap(0, cd.size, PROT_READ | PROT_WRITE, MAP_SHARED, fd,
                        (off_t)md.offset);
  if (fbuf == MAP_FAILED) die("mmap");
  uint32_t npix = (uint32_t)(cd.size / 4);
  for (uint32_t i = 0; i < npix; i++) fbuf[i] = 0xFFFFFFFF; /* white */

  struct drm_mode_crtc crtc;
  memset(&crtc, 0, sizeof crtc);
  crtc.crtc_id = chosen_crtc;
  crtc.fb_id = fb.fb_id;
  crtc.set_connectors_ptr = (uint64_t)(uintptr_t)&chosen_conn;
  crtc.count_connectors = 1;
  crtc.mode = mode;
  crtc.mode_valid = 1;
  if (ioctl(fd, DRM_IOCTL_MODE_SETCRTC, &crtc) < 0) die("SETCRTC");
  fprintf(stderr, "modeset ok: %ux%u fb %u\n", mode.hdisplay, mode.vdisplay,
          fb.fb_id);

  struct drm_mode_fb_dirty_cmd dirty;
  memset(&dirty, 0, sizeof dirty);
  dirty.fb_id = fb.fb_id;
  dirty.flags = DRM_MODE_FB_DIRTY_ANNOTATE_COPY;
  if (ioctl(fd, DRM_IOCTL_MODE_DIRTYFB, &dirty) < 0) perror("DIRTYFB");

  sleep(2);

  memset(&crtc, 0, sizeof crtc);
  crtc.crtc_id = chosen_crtc;
  if (ioctl(fd, DRM_IOCTL_MODE_SETCRTC, &crtc) < 0) perror("SETCRTC clear");
  if (ioctl(fd, DRM_IOCTL_MODE_RMFB, &fb.fb_id) < 0) perror("RMFB");
  struct drm_mode_destroy_dumb dd;
  memset(&dd, 0, sizeof dd);
  dd.handle = cd.handle;
  ioctl(fd, DRM_IOCTL_MODE_DESTROY_DUMB, &dd);
  munmap(fbuf, cd.size);
  fprintf(stderr, "modeset done\n");
  return 0;
}
