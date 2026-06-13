/*
 * tap_inject.c
 * Root-native touch injector using Linux multi-touch protocol B.
 * Usage: tap_inject /dev/input/eventX <x> <y>
 *
 * Writes struct input_event directly — bypasses AccessibilityManagerService,
 * InputDispatcher, and SurfaceFlinger pipeline entirely.
 * Latency: ~2-4ms vs ~40-60ms for dispatchGesture().
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <linux/input.h>
#include <sys/time.h>

/* Write a single input_event to the device fd */
static int write_event(int fd, __u16 type, __u16 code, __s32 value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, NULL);
    ev.type  = type;
    ev.code  = code;
    ev.value = value;
    ssize_t written = write(fd, &ev, sizeof(ev));
    return (written == sizeof(ev)) ? 0 : -1;
}

/* Send SYN_REPORT — commits the event batch to the kernel */
static int syn(int fd) {
    return write_event(fd, EV_SYN, SYN_REPORT, 0);
}

int main(int argc, char *argv[]) {
    if (argc != 4) {
        fprintf(stderr, "Usage: tap_inject <event_node> <x> <y>\n");
        fprintf(stderr, "Example: tap_inject /dev/input/event3 540 960\n");
        return 1;
    }

    const char *node = argv[1];
    int x = atoi(argv[2]);
    int y = atoi(argv[3]);

    int fd = open(node, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "tap_inject: cannot open %s\n", node);
        return 1;
    }

    /* ── Touch DOWN ──────────────────────────────────────────────────────── */
    write_event(fd, EV_ABS, ABS_MT_TRACKING_ID, 1);   /* slot active        */
    write_event(fd, EV_ABS, ABS_MT_POSITION_X,  x);   /* X coordinate       */
    write_event(fd, EV_ABS, ABS_MT_POSITION_Y,  y);   /* Y coordinate       */
    write_event(fd, EV_KEY, BTN_TOUCH,           1);   /* finger down        */
    syn(fd);                                            /* commit DOWN        */

    /* 50ms contact duration — long enough for any app to register the tap  */
    usleep(50000);

    /* ── Touch UP ────────────────────────────────────────────────────────── */
    write_event(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);  /* slot released      */
    write_event(fd, EV_KEY, BTN_TOUCH,           0);   /* finger up          */
    syn(fd);                                            /* commit UP          */

    close(fd);
    return 0;
}
