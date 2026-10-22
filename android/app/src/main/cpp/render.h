#ifndef RENDER_H
#define RENDER_H
#include <stdbool.h>
#include <stdint.h>

// Renderer (render.c); called from the GL thread.
void renderInit(void);
void renderResize(int w, int h);
void renderDraw(int filter);
void renderSetPictureRect(int left, int top, int width, int height);
void renderSetAmbient(bool on);

// Provided by bridge.c. Acquire returns false (lock not held) when there is no frame;
// otherwise the frame mutex stays held until bridgeFrameRelease(consumedNew).
bool bridgeFrameAcquire(const uint32_t** px, int* w, int* h, bool* isNew);
void bridgeFrameRelease(bool consumedNew);
void bridgeFrameForceNew(void);
#endif
