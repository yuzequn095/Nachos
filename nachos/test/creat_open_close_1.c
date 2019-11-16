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


    close(fd2);
    close(fd3);
    close(fd4);

    //input size > 256b
    char str[257];
    memset(str, 'a', 257);
    str[256] = '\0';

    int fd5 = open( str );
    printf("open name was longer than 256 bytes, fd is %d\n", fd5);

    fd5 = creat( str );
    printf("creat name was longer than 256 bytes, fd is %d\n", fd5);

    /*
    //Check num of descriptors
    int i = 3; //3 descriptors currently openi */
    int fds[16] = {-2};
    char temp[16];
    /*
    fds[0] = fd2;
    fds[1] = fd3;
    fds[2] = fd4; */
    int i = 1;
    while( i < 17 ){
        sprintf( temp, "%d", i );
        //printf( temp );
        fds[i] = creat(temp);
        printf( "%dth fd created is %d\n", i, fds[i]);
        i++;
    }
    /*
    printf("15th fd created is %d\n", fds[14]);
    printf("last fd created is %d\n", fds[15]);
    */
}