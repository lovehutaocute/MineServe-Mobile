package com.mineserve.mobile.ftp

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

/**
 * FTP 服务器管理器：在应用内运行一个 FTP 服务，对外暴露指定根目录。
 *
 * 使用 Apache FTPServer（纯 Java 实现），单例，同一时刻只允许一个 FTP 服务运行。
 * 根目录、端口、用户名、密码在 [start] 时传入；支持匿名访问（用户名留空）；
 * 停止后可重新启动。
 */
object FtpServerManager {

    private const val TAG = "FtpServerManager"

    @Volatile
    private var server: FtpServer? = null

    private val _running = MutableStateFlow(false)

    /** FTP 服务运行状态（UI 订阅实时刷新） */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val isRunning: Boolean
        get() = server?.isStopped == false && server?.isSuspended == false

    /**
     * 启动 FTP 服务。
     *
     * @param rootDir FTP 根目录（客户端只能访问此目录内的文件）
     * @param port 监听端口
     * @param username 登录用户名（空则启用匿名访问）
     * @param password 登录密码（匿名访问时忽略）
     * @param writable 是否允许写入（上传/删除/重命名）
     * @param ipv6Enabled 是否启用 IPv6（双栈）监听；关闭时仅监听 IPv4。
     *   serverAddress="::" 在 Android（内核 bindv6only=0）上为双栈，可同时接受
     *   IPv4 与 IPv6 连接；"0.0.0.0" 则仅监听 IPv4。IPv6 客户端通过 EPSV 协商
     *   被动数据连接（FTPServer 默认支持），无需额外配置。
     */
    @Synchronized
    fun start(rootDir: String, port: Int, username: String, password: String, writable: Boolean, ipv6Enabled: Boolean) {
        if (isRunning) throw IllegalStateException("FTP 服务已在运行")

        val root = File(rootDir)
        if (!root.isDirectory) root.mkdirs()

        val serverFactory = FtpServerFactory()

        // 配置监听器（端口与绑定地址）
        val listenerFactory = ListenerFactory()
        listenerFactory.port = port
        listenerFactory.serverAddress = if (ipv6Enabled) "::" else "0.0.0.0"
        serverFactory.addListener("default", listenerFactory.createListener())

        // 配置用户：匿名或具名，home 目录限定为 rootDir。
        // 匿名用户不设置密码（保持 null）：FTPServer 对 null 密码跳过校验，
        // 标准客户端匿名登录时会发送任意密码（如 ftp@example.com），
        // 若设为空字符串会做精确匹配导致登录被拒。
        val userManagerFactory = PropertiesUserManagerFactory()
        val userManager = userManagerFactory.createUserManager()
        val user = BaseUser().apply {
            name = if (username.isBlank()) "anonymous" else username
            if (username.isNotBlank()) {
                this.password = password
            }
            homeDirectory = root.absolutePath
            if (writable) {
                authorities = listOf(WritePermission())
            }
        }
        userManager.save(user)
        serverFactory.userManager = userManager

        val srv = serverFactory.createServer()
        srv.start()
        server = srv
        _running.value = true
        Log.i(TAG, "FTP 服务已启动: $rootDir @ :$port (${if (username.isBlank()) "匿名" else username})")
    }

    /** 停止 FTP 服务 */
    @Synchronized
    fun stop() {
        server?.let {
            if (!it.isStopped) {
                it.stop()
            }
        }
        server = null
        _running.value = false
        Log.i(TAG, "FTP 服务已停止")
    }
}
