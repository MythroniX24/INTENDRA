/*
 * termux.c — JNI native interface for PTY-based terminal subprocesses.
 *
 * Adapted from Termux's terminal-emulator module:
 *   https://github.com/termux/termux-app/blob/master/terminal-emulator/src/main/jni/termux.c
 *
 * Provides:
 *   - createSubprocess() — fork/exec with pseudo-terminal (PTY)
 *   - setPtyWindowSize() — ioctl(TIOCSWINSZ) for terminal resize
 *   - setPtyUTF8Mode()   — enable IUTF8 on PTY
 *   - waitFor()          — waitpid() wrapper
 *   - close()            — close() wrapper
 */

#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define UNUSED(x) x __attribute__((__unused__))

/* ── Utility: throw a Java RuntimeException ────────────────────────────── */
static int throw_runtime_exception(JNIEnv* env, char const* message)
{
    jclass exClass = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, exClass, message);
    return -1;
}

/* ── Core: create a subprocess with a pseudo-terminal ─────────────────── */
static int create_subprocess(JNIEnv* env,
                             char const* cmd,
                             char const* cwd,
                             char* const argv[],
                             char** envp,
                             int* pProcessId,
                             jint rows,
                             jint columns,
                             jint cell_width,
                             jint cell_height)
{
    /* Open master side of PTY */
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0)
        return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    /* Grant access and unlock slave */
    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        return throw_runtime_exception(env, "grantpt()/unlockpt()/ptsname_r() failed");
    }

    /* Configure terminal: enable UTF-8, disable flow control (Ctrl+S/Q) */
    struct termios tios;
    tcgetattr(ptm, &tios);
    tios.c_iflag |= IUTF8;
    tios.c_iflag &= ~(IXON | IXOFF);
    tcsetattr(ptm, TCSANOW, &tios);

    /* Set initial window size */
    struct winsize sz = {
        .ws_row    = (unsigned short) rows,
        .ws_col    = (unsigned short) columns,
        .ws_xpixel = (unsigned short) (columns * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    ioctl(ptm, TIOCSWINSZ, &sz);

    /* Fork */
    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "fork() failed");
    }

    if (pid > 0) {
        /* ── Parent: return PTY master fd and child PID ── */
        *pProcessId = (int) pid;
        return ptm;
    }

    /* ══════════════════════════════════════════════════════
       ── CHILD PROCESS ────────────────────────────────────
       ══════════════════════════════════════════════════════ */

    /* Unblock all signals that Java may have blocked */
    sigset_t signals_to_unblock;
    sigfillset(&signals_to_unblock);
    sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL);

    close(ptm);

    /* Create new session — child becomes session leader */
    setsid();

    /* Open slave PTY */
    int pts = open(devname, O_RDWR);
    if (pts < 0) _exit(-1);

    /* Redirect stdin/stdout/stderr to PTY slave */
    dup2(pts, STDIN_FILENO);
    dup2(pts, STDOUT_FILENO);
    dup2(pts, STDERR_FILENO);

    /* Close all other file descriptors */
    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir != NULL) {
        int self_dir_fd = dirfd(self_dir);
        struct dirent* entry;
        while ((entry = readdir(self_dir)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd > 2 && fd != self_dir_fd) close(fd);
        }
        closedir(self_dir);
    }

    /* Set environment */
    clearenv();
    if (envp) {
        for (char** e = envp; *e; ++e) putenv(*e);
    }

    /* Change to working directory */
    if (cwd && cwd[0] != '\0') {
        if (chdir(cwd) != 0) {
            char* error_message;
            if (asprintf(&error_message, "chdir(\"%s\")", cwd) == -1)
                error_message = "chdir()";
            perror(error_message);
            fflush(stderr);
        }
    }

    /* Execute the shell */
    execvp(cmd, argv);

    /* If execvp returns, it failed */
    char* error_message;
    if (asprintf(&error_message, "exec(\"%s\")", cmd) == -1)
        error_message = "exec()";
    perror(error_message);
    fflush(stderr);
    _exit(1);

    return -1; /* unreachable */
}

/* ══════════════════════════════════════════════════════════════════════════
   JNI EXPORTED FUNCTIONS
   Package: com.interndra.jni
   Class:   JniTermux
   ══════════════════════════════════════════════════════════════════════════ */

/*
 * Java_com_interndra_jni_JniTermux_createSubprocess
 *
 * Creates a subprocess with a PTY. Returns the PTY master file descriptor.
 *
 * @param cmd         Shell executable path (e.g., /data/local/tmp/.../usr/bin/bash)
 * @param cwd         Working directory for the child process
 * @param args        String[] of arguments (first should be the command name)
 * @param envVars     String[] of "KEY=VALUE" environment variables
 * @param processIdArray  int[1] output — will be filled with the child PID
 * @param rows        Terminal rows
 * @param columns     Terminal columns
 * @param cell_width  Cell width in pixels
 * @param cell_height Cell height in pixels
 * @return PTY master file descriptor (int), or throws RuntimeException on failure
 */
JNIEXPORT jint JNICALL
Java_com_interndra_jni_JniTermux_createSubprocess(
    JNIEnv* env,
    jclass UNUSED(clazz),
    jstring cmd,
    jstring cwd,
    jobjectArray args,
    jobjectArray envVars,
    jintArray processIdArray,
    jint rows,
    jint columns,
    jint cell_width,
    jint cell_height)
{
    /* ── Parse argv ─────────────────────────────────────────────────── */
    jsize argc = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (argc > 0) {
        argv = (char**) malloc((argc + 1) * sizeof(char*));
        if (!argv)
            return throw_runtime_exception(env, "malloc() for argv failed");
        for (int i = 0; i < argc; ++i) {
            jstring js = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* utf = (*env)->GetStringUTFChars(env, js, NULL);
            if (!utf) {
                for (int k = 0; k < i; ++k) free(argv[k]);
                free(argv);
                return throw_runtime_exception(env, "GetStringUTFChars() failed for argv");
            }
            argv[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, js, utf);
        }
        argv[argc] = NULL;
    }

    /* ── Parse envp ─────────────────────────────────────────────────── */
    jsize envc = envVars ? (*env)->GetArrayLength(env, envVars) : 0;
    char** envp = NULL;
    if (envc > 0) {
        envp = (char**) malloc((envc + 1) * sizeof(char*));
        if (!envp) {
            if (argv) { for (char** t = argv; *t; ++t) free(*t); free(argv); }
            return throw_runtime_exception(env, "malloc() for envp failed");
        }
        for (int i = 0; i < envc; ++i) {
            jstring js = (jstring) (*env)->GetObjectArrayElement(env, envVars, i);
            char const* utf = (*env)->GetStringUTFChars(env, js, NULL);
            if (!utf) {
                for (int k = 0; k < i; ++k) free(envp[k]);
                free(envp);
                if (argv) { for (char** t = argv; *t; ++t) free(*t); free(argv); }
                return throw_runtime_exception(env, "GetStringUTFChars() failed for envp");
            }
            envp[i] = strdup(utf);
            (*env)->ReleaseStringUTFChars(env, js, utf);
        }
        envp[envc] = NULL;
    }

    /* ── Parse strings ─────────────────────────────────────────────── */
    char const* cmd_utf8  = (*env)->GetStringUTFChars(env, cmd, NULL);
    char const* cwd_utf8  = (*env)->GetStringUTFChars(env, cwd, NULL);

    int procId = 0;
    int ptm = create_subprocess(env, cmd_utf8, cwd_utf8,
                                 argv, envp,
                                 &procId,
                                 rows, columns, cell_width, cell_height);

    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);

    /* Clean up allocated memory */
    if (argv) { for (char** t = argv; *t; ++t) free(*t); free(argv); }
    if (envp) { for (char** t = envp; *t; ++t) free(*t); free(envp); }

    /* Write process ID back to Java array */
    jint* pProcId = (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);
    if (!pProcId) {
        close(ptm);
        return throw_runtime_exception(env, "GetPrimitiveArrayCritical() failed");
    }
    *pProcId = (jint) procId;
    (*env)->ReleasePrimitiveArrayCritical(env, processIdArray, pProcId, 0);

    return ptm;
}

/*
 * Java_com_interndra_jni_JniTermux_setPtyWindowSize
 *
 * Update the terminal window size via ioctl(TIOCSWINSZ).
 */
JNIEXPORT void JNICALL
Java_com_interndra_jni_JniTermux_setPtyWindowSize(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint fd,
    jint rows,
    jint cols,
    jint cell_width,
    jint cell_height)
{
    struct winsize sz = {
        .ws_row    = (unsigned short) rows,
        .ws_col    = (unsigned short) cols,
        .ws_xpixel = (unsigned short) (cols * cell_width),
        .ws_ypixel = (unsigned short) (rows * cell_height)
    };
    ioctl(fd, TIOCSWINSZ, &sz);
}

/*
 * Java_com_interndra_jni_JniTermux_setPtyUTF8Mode
 *
 * Enable UTF-8 mode (IUTF8) on the PTY.
 */
JNIEXPORT void JNICALL
Java_com_interndra_jni_JniTermux_setPtyUTF8Mode(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint fd)
{
    struct termios tios;
    tcgetattr(fd, &tios);
    if ((tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

/*
 * Java_com_interndra_jni_JniTermux_waitFor
 *
 * Block until the child process exits. Returns exit code (positive) or
 * negative signal number if killed by a signal.
 */
JNIEXPORT jint JNICALL
Java_com_interndra_jni_JniTermux_waitFor(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint pid)
{
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
        return -WTERMSIG(status);
    } else {
        return 0;
    }
}

/*
 * Java_com_interndra_jni_JniTermux_close
 *
 * Close a file descriptor.
 */
JNIEXPORT void JNICALL
Java_com_interndra_jni_JniTermux_close(
    JNIEnv* UNUSED(env),
    jclass UNUSED(clazz),
    jint fileDescriptor)
{
    close(fileDescriptor);
}
