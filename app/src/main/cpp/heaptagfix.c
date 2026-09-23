/*
 * libheaptagfix.so —— 在 JVM 分配内存之前关闭 bionic 的堆指针标签。
 *
 * ## 为什么需要这个
 *
 * Android 11（API 30）起，bionic 的 malloc 默认会给堆分配打上指针标签
 * （heap pointer tagging，ARM64 的 TBI/MTE 机制）。指针高 8 位被用来存放
 * 分配元数据。
 *
 * JVM（HotSpot）内部会直接改写对象指针的高位字节做元数据（例如 compressed
 * oops、标记位）。在带标签的堆上，这会：
 *
 *   1. 释放时标签校验失败 → SIGABRT（退出码 134）
 *   2. 分配器无法复用被改写过的内存块 → 常驻内存（RSS）显著上升
 *   3. 每个分配多出标签元数据，JVM 启动期海量小对象分配累计开销可观
 *
 * 第 2、3 条正是「同一个服务端、同一份 jar，内存却比别人的实现高 100MB 左右」
 * 的直接原因。
 *
 * ## 为什么用 LD_PRELOAD 而不是在 App 进程里调用
 *
 * mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0) 只对「调用它的那个进程」生效，
 * 且必须在任何 malloc 之前执行。
 *
 * 而 Minecraft 服务端是 App 通过 ProcessBuilder 拉起的**独立进程**，App 自己
 * 调用 mallopt 完全影响不到它。
 *
 * 因此这里用 LD_PRELOAD：把本库注入到 java 进程里。linker 在加载任何其他
 * 库（含 libc 的 malloc 首次使用）之前会先执行本库的构造函数，此时调用
 * mallopt 正好赶在所有 JVM 分配之前。
 *
 * 这也正是 EdgeCube 采用 mallopt 的同一思路，区别是它自己写 JVM 启动器，
 * 而我们不改动启动方式、纯靠 LD_PRELOAD 注入。
 *
 * ## 安全性
 *
 * - 所有操作在构造函数里完成，无任何导出函数，不介入 JVM 运行。
 * - mallopt 或 android_mallopt 不存在时静默跳过（老设备 / 非 bionic），
 *   绝不影响 java 正常启动。
 * - 构造函数不调用任何可能触发 malloc 的 API（不用 printf/fprintf 等），
 *   避免在分配器初始化过程中递归进入。
 *
 * ## 产物与重新编译（重要）
 *
 * 本库**不随常规构建编译**。项目的构建刻意不依赖 NDK（见 app/build.gradle.kts 里
 * jniLibs.keepDebugSymbols 的说明），所以产物以**预编译形式入库**：
 *
 *     app/src/main/jniLibs/arm64-v8a/libheaptagfix.so
 *
 * 它和同目录下那 19 个 Termux 依赖库一样，是提交进仓库的二进制。
 * `./gradlew assembleDebug` **不会**编译本文件 —— 改了这里却不重新生成产物，
 * 改动不会生效（这正是 1.2.8 之前的状态：Java 侧一直在 LD_PRELOAD 一个
 * 从未被打进 APK 的库名，功能静默失效）。
 *
 * 重新生成：手动触发 GitHub Actions 的「Build heaptagfix native lib」工作流
 * （.github/workflows/build-heaptagfix.yml），它会编译并把产物提交回 master。
 *
 * 等价的手工命令（NDK r27，minSdk 26）：
 *
 *     $NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android26-clang \
 *         -shared -fPIC -Oz -fvisibility=hidden \
 *         -ffunction-sections -fdata-sections \
 *         -Wl,--gc-sections -Wl,-z,max-page-size=16384 \
 *         -o libheaptagfix.so heaptagfix.c
 *
 * 产物只依赖 libc.so 与 libdl.so（NDK 驱动默认记录这两个 NEEDED）。二者在
 * Android 上由 linker 在进程启动时就已加载，所以这个库不会给 java 进程引入任何
 * 新的加载依赖 —— 这一点由工作流里的 readelf 自检把守：出现别的依赖即判定失败。
 */

/*
 * 刻意不包含任何 libc 头文件（<stddef.h> / <stdint.h> 等在交叉编译时会拉入
 * glibc 的 bits/ 头，而目标平台是 bionic）。
 * 本库只用 dlsym 一个外部符号，其余全为自带定义，因此完全不需要头文件。
 */
typedef __SIZE_TYPE__ size_t;

#ifndef NULL
#define NULL ((void *)0)
#endif

/* 不使用任何头文件里的 malloc/calloc，全部走运行时查找，避免引入额外依赖。
 * RTLD_DEFAULT 的数值在各 Android 版本一致，但为稳妥仍用显式声明。
 */
extern void *dlsym(void *handle, const char *symbol);

/* RTLD_DEFAULT 在 glibc/bionic 上都是 ((void *) 0)，linker 会按全局作用域查找 */
#define RTLD_DEFAULT_HANDLE ((void *)0)

/* 常量取值来自 bionic <malloc.h>：
 *   M_BIONIC_SET_HEAP_TAGGING_LEVEL = 55
 *   M_HEAP_TAGGING_LEVEL_NONE       = 0   （不打标签）
 * ANDROID_MALLOPT_HEAP_TAGGING_LEVEL = 8（android_mallopt 的新接口）
 */
#define M_BIONIC_SET_HEAP_TAGGING_LEVEL 55
#define M_HEAP_TAGGING_LEVEL_NONE 0
#define ANDROID_MALLOPT_HEAP_TAGGING_LEVEL 8

typedef int (*mallopt_fn)(int, int);
typedef int (*android_mallopt_fn)(int, void *, size_t);

/*
 * 关闭堆指针标签。返回 0 表示成功，非 0 表示未能关闭（不影响程序继续运行）。
 *
 * 分两步尝试，因为不同 Android 版本暴露的接口不同：
 *   1. mallopt(55, 0)                   —— Android 11 ~ 13 可用，最直接
 *   2. android_mallopt(8, &level, ...)  —— Android 13+ 的新接口，level=0
 */
static int disable_heap_tagging(void)
{
    mallopt_fn mallopt_ptr =
        (mallopt_fn)dlsym(RTLD_DEFAULT_HANDLE, "mallopt");
    if (mallopt_ptr != NULL) {
        if (mallopt_ptr(M_BIONIC_SET_HEAP_TAGGING_LEVEL,
                        M_HEAP_TAGGING_LEVEL_NONE) != 0) {
            /* 返回非 0 表示「已经来不及改了」（分配器已初始化）。
             * 这时不再尝试第二个接口，因为结果一样。 */
            return 1;
        }
        return 0;
    }

    /* mallopt 不可用（新版本 bionic 可能移除），退到 android_mallopt */
    android_mallopt_fn android_mp =
        (android_mallopt_fn)dlsym(RTLD_DEFAULT_HANDLE, "android_mallopt");
    if (android_mp != NULL) {
        int level = M_HEAP_TAGGING_LEVEL_NONE;
        if (android_mp(ANDROID_MALLOPT_HEAP_TAGGING_LEVEL,
                       (void *)&level, sizeof(level)) == 0) {
            return 0;
        }
    }
    return 1;
}

/*
 * 构造函数：在共享库被加载时（早于 JVM 任何分配）立即执行。
 *
 * __attribute__((constructor)) 由 linker 在 dlopen/依赖解析阶段调用，
 * 早于 Java main 与一切 JVM 堆初始化。
 */
__attribute__((constructor))
static void heaptagfix_init(void)
{
    (void)disable_heap_tagging();
}
