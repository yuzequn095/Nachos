#include "syscall.h"
#include "stdio.h"

int main()
{
    int fd1 = open("test_doc");
    printf("test_doc opened, fd is %d\n", fd1);

    int fd2 = creat("test");
    printf("empty file 'test' created. fd is %d\n", fd2);
    printf("(if you set the max opened file per proc to be 3, test's fd should be -1)\n");

    int fd3 = open("test");
    printf("open test again, fd is %d\n", fd3);

    close(fd1);
    printf("close test_doc now\n");
    int fd4 = creat("test2");
    printf("create 'test2', fd is %d\n", fd4);

    printf("=== Super long file name creation test ===\n");
    char *name = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ12345678910111213141516171819202122232425262728293031323334353637383940414243444546474849505152535455565758596061626364656667686970717273747576777879808182838485868788899091929394959697989910012345678910111213141516171819202122232425262728293031323334353637383940";
    int fd5 = creat(name);
    printf("fd of the long name file is %d\n", fd5);
    if (fd5 == -1) printf("long name file test PASS\n\n");

    close(fd1);
    close(fd2);
    close(fd3);
    close(fd4);
    close(fd5);

    printf("\n== max number of open file test ==\n");
    int fds[20];
    int i;
    for (i = 0; i < 20; i++) {
        fds[i] = open("test_doc");
        if (fds[i] > 1) printf("Successfully open the %d th fd: %d\n", i+1, fds[i]);
        else printf("Failed to open the %d th fd ...\n", i+1);
    }

    /* not reached */
}

