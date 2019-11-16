/*
 * creat.c
 * test if creat creat a file corresponding to the filename
 * test if the created file can be opened
 */

#include "stdio.h"
#include "stdlib.h"

int
do_open (char *fname) {
    int fd;

    printf ("opening %s...\n", fname);
    fd = open (fname);
    if (fd >= 0) {
	printf ("...passed (fd = %d)\n", fd);
    } else {
	printf ("...failed (%d)\n", fd);
	exit (-1002);
    }
    return fd;
}

int
do_creat (char *fname) {
    int fd;

    printf ("creating %s...\n", fname);
    fd = creat (fname);
    if (fd >= 0) {
	printf ("...passed (fd = %d)\n", fd);
    } else {
	printf ("...failed (%d)\n", fd);
	exit (-1001);
    }
    return fd;
}

void
do_close (int fd) {
    int r;

    printf ("closing %d...\n", fd);
    r = close (fd);
    if (r < 0) {
	printf ("...failed (r = %d)\n", r);
	exit (-1003);
    }
}

int test_creat_open(char *fname) {
    do_creat(fname);
    fd = do_open(fname);
    do_close(fd);
}

int main () {
    fname = "creat.out";
    test_creat_open(fname);
}