package com.mineserve.mobile.ftp

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * 实测 Apache FTPServer 的匿名登录行为，确定 FtpServerManager 匿名用户的正确配置：
 *  - 密码设为空字符串：客户端发送任意密码时能否登录？
 *  - 密码不设置（null）：客户端发送任意密码时能否登录？
 */
class FtpServerAnonymousTest {

    /** 用 FtpServerManager 相同的配置方式启动一个匿名 FTP，返回端口 */
    private fun startAnonymousServer(setPassword: Boolean): Int {
        val freePort = ServerSocket(0).use { it.localPort }
        val root = File(System.getProperty("java.io.tmpdir"), "ftp-test-${System.nanoTime()}").apply { mkdirs() }

        val serverFactory = FtpServerFactory()
        val listenerFactory = ListenerFactory()
        listenerFactory.port = freePort
        listenerFactory.serverAddress = "127.0.0.1"
        serverFactory.addListener("default", listenerFactory.createListener())

        val userManagerFactory = PropertiesUserManagerFactory()
        val userManager = userManagerFactory.createUserManager()
        val user = BaseUser().apply {
            name = "anonymous"
            if (setPassword) this.password = ""
            homeDirectory = root.absolutePath
        }
        userManager.save(user)
        serverFactory.userManager = userManager

        val server: FtpServer = serverFactory.createServer()
        server.start()
        return freePort
    }

    /** 极简 FTP 客户端：USER/PASS 登录，返回 PASS 后的响应码 */
    private fun login(port: Int, user: String, pass: String): Int {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            fun readCode(): Int {
                var code = 0
                var line = reader.readLine()
                while (line != null) {
                    code = line.takeIf { it.length >= 3 }?.substring(0, 3)?.toIntOrNull() ?: 0
                    if (line.length <= 3 || line[3] != '-') break
                    line = reader.readLine()
                }
                return code
            }

            assertEquals(220, readCode())
            writer.println("USER $user")
            val userCode = readCode()
            if (userCode == 230) return 230
            writer.println("PASS $pass")
            return readCode()
        }
    }

    /**
     * 实测结论：用户名为 "anonymous" 时，FTPServer 忽略密码校验——
     * 密码设为空串或不设置，客户端发任意密码都能登录（FTP 匿名惯例）。
     * 匿名登录被拒的唯一情形是服务器上根本不存在 anonymous 用户（开关关闭）。
     */
    @Test
    fun anonymousWithEmptyPasswordAcceptsAnyPassword() {
        val port = startAnonymousServer(setPassword = true)
        assertEquals(230, login(port, "anonymous", ""))
        assertEquals(230, login(port, "anonymous", "guest@x.com"))
    }

    @Test
    fun anonymousWithoutPasswordAcceptsAnyPassword() {
        val port = startAnonymousServer(setPassword = false)
        assertEquals(230, login(port, "anonymous", ""))
        assertEquals(230, login(port, "anonymous", "guest@x.com"))
    }

    @Test
    fun namedUserWithEmptyPasswordRejectsWrongPassword() {
        val freePort = ServerSocket(0).use { it.localPort }
        val root = File(System.getProperty("java.io.tmpdir"), "ftp-test-${System.nanoTime()}").apply { mkdirs() }
        val serverFactory = FtpServerFactory()
        val listenerFactory = ListenerFactory()
        listenerFactory.port = freePort
        listenerFactory.serverAddress = "127.0.0.1"
        serverFactory.addListener("default", listenerFactory.createListener())
        val userManagerFactory = PropertiesUserManagerFactory()
        val userManager = userManagerFactory.createUserManager()
        val user = BaseUser().apply {
            name = "mc"
            password = ""
            homeDirectory = root.absolutePath
        }
        userManager.save(user)
        serverFactory.userManager = userManager
        val server: FtpServer = serverFactory.createServer()
        server.start()

        assertEquals(230, login(freePort, "mc", "")) // 空密码精确匹配
        assertEquals(530, login(freePort, "mc", "wrong")) // 具名用户拒绝错误密码
        assertEquals(530, login(freePort, "nobody", "x")) // 不存在的用户拒绝
        server.stop()
    }
}
