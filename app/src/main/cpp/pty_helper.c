#include <jni.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

/*
 * Minimal PTY helper for running mosh-client on Android.
 * Creates a pseudo-terminal, forks, and execs the given command.
 * Returns [masterFd, childPid] to Java.
 */

JNIEXPORT jintArray JNICALL
Java_com_vscodetunnel_app_PtyProcess_nativeForkPty(
    JNIEnv *env, jclass clazz,
    jstring jCmd, jobjectArray jArgs, jobjectArray jEnv,
    jint rows, jint cols)
{
    int master;
    pid_t pid;

    // Open PTY master
    master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return NULL;
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        close(master);
        return NULL;
    }

    // Set window size
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    ioctl(master, TIOCSWINSZ, &ws);

    const char *slaveName = ptsname(master);
    if (!slaveName) { close(master); return NULL; }

    pid = fork();
    if (pid < 0) {
        close(master);
        return NULL;
    }

    if (pid == 0) {
        // Child process
        close(master);
        setsid();

        int slave = open(slaveName, O_RDWR);
        if (slave < 0) _exit(127);

        // Make slave the controlling terminal
        ioctl(slave, TIOCSCTTY, 0);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > 2) close(slave);

        // Build argv
        int argc = (*env)->GetArrayLength(env, jArgs);
        char **argv = calloc(argc + 1, sizeof(char *));
        for (int i = 0; i < argc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, jArgs, i);
            argv[i] = strdup((*env)->GetStringUTFChars(env, s, NULL));
        }
        argv[argc] = NULL;

        // Build envp
        int envc = (*env)->GetArrayLength(env, jEnv);
        char **envp = calloc(envc + 1, sizeof(char *));
        for (int i = 0; i < envc; i++) {
            jstring s = (*env)->GetObjectArrayElement(env, jEnv, i);
            envp[i] = strdup((*env)->GetStringUTFChars(env, s, NULL));
        }
        envp[envc] = NULL;

        const char *cmd = (*env)->GetStringUTFChars(env, jCmd, NULL);
        execve(cmd, argv, envp);
        _exit(127);
    }

    // Parent - return [masterFd, pid]
    jintArray result = (*env)->NewIntArray(env, 2);
    jint vals[2] = { master, (jint)pid };
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;
}

JNIEXPORT void JNICALL
Java_com_vscodetunnel_app_PtyProcess_nativeSetWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols)
{
    struct winsize ws = { .ws_row = rows, .ws_col = cols };
    ioctl(fd, TIOCSWINSZ, &ws);
}
