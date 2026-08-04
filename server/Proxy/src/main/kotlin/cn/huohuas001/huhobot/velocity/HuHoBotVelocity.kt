package cn.huohuas001.huhobot.velocity

import cn.huohuas001.bot.ClientManager
import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.BaseEvent
import cn.huohuas001.bot.events.BindRequest
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobot.common.HuHoBotProxy
import cn.huohuas001.huhobot.velocity.commands.HuHoBotCommand
import cn.huohuas001.huhobot.velocity.commands.VelocityConsoleSender
import cn.huohuas001.huhobot.velocity.events.GameChat
import cn.huohuas001.huhobot.velocity.events.PlayerEvents
import cn.huohuas001.huhobot.velocity.events.QueryAllowList
import cn.huohuas001.huhobot.velocity.events.QueryOnline
import cn.huohuas001.huhobot.common.managers.IConfigManager
import cn.huohuas001.huhobot.velocity.managers.ConfigManager
import cn.huohuas001.huhobot.common.redis.RedisManager
import com.alibaba.fastjson2.JSONObject
import com.google.inject.Inject
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import org.slf4j.Logger
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class HuHoBotVelocity @Inject constructor(
    val server: ProxyServer,
    val logger: Logger,
    @DataDirectory val dataDirectory: Path,
    private val pluginContainer: PluginContainer
) : HuHoBotProxy {

    override lateinit var configManager: IConfigManager
    override var redisManager: RedisManager? = null
    override var bindRequestObj = BindRequest()
    override var eventList: MutableMap<String, BaseEvent> = HashMap()

    private val whitelistChannel = MinecraftChannelIdentifier.create("huhostdwhitelist", "main")
    private val packIdMap = ConcurrentHashMap<String, String>()
    private val pendingBinds = ConcurrentLinkedQueue<String>()

    @Subscribe
    fun onProxyInitialization(event: ProxyInitializeEvent) {
        configManager = ConfigManager(this)
        configManager.loadCommandsFromConfig()

        // 初始化 Redis
        initRedis()

        // Register command
        val commandMeta: CommandMeta = server.commandManager.metaBuilder("huhobot")
            .aliases("hb")
            .build()
        server.commandManager.register(commandMeta, HuHoBotCommand(this))

        // Register plugin message channel
        server.channelRegistrar.register(whitelistChannel)

        // Register chat event listener
        server.eventManager.register(this, GameChat(this))

        server.eventManager.register(this, PlayerEvents(this))

        // 注册自身监听 PluginMessageEvent 和 ServerPostConnectEvent
        server.eventManager.register(this, this)

        enableBot()
    }

    private fun initRedis() {
        if (configManager.isRedisEnabled()) {
            redisManager = RedisManager(this)
            redisManager!!.connect(
                configManager.getRedisHost(),
                configManager.getRedisPort(),
                configManager.getRedisPassword()
            )
        } else {
            logger.info("Redis 未启用，命令将在本地执行")
        }
    }

    @Subscribe
    fun onProxyShutdown(event: ProxyShutdownEvent) {
        redisManager?.disconnect()
        ClientManager.setShouldReconnect(false)
        ClientManager.shutdownClient()
    }

    override fun broadcastMessage(msg: String) {
        server.allPlayers.forEach { player ->
            player.sendMessage(net.kyori.adventure.text.Component.text(msg))
        }
        redisManager?.broadcast(msg)
    }

    override fun callPluginEvent(command: String, data: JSONObject, packId: String, runByAdmin: Boolean): Boolean {
        val params = data.getList("runParams", String::class.java)
        val code = if (params.isNotEmpty()) params[0] ?: "" else ""
        val author = data.getJSONObject("author")
        val openId = author?.getString("openId") ?: ""

        if (code.isNotEmpty() && openId.isNotEmpty()) {
            // 存储 packId，等 Paper 回报后发送 QQ 消息
            packIdMap[code] = packId

            val msg = "BIND|$code|$openId"
            // 缓存消息，防止零玩家时消息丢失
            pendingBinds.add(msg)
            sendBindToAllServers(msg)
            logger.info("HuHoSTDWhiteList bind: code=$code openId=$openId")
            return true
        }
        return false
    }

    private fun sendBindToAllServers(msg: String) {
        server.allServers.forEach { s ->
            try {
                s.sendPluginMessage(whitelistChannel, msg.toByteArray())
            } catch (e: Exception) {
                logger.warn("Failed to send bind to {}: {}", s.serverInfo.name, e.message)
            }
        }
    }

    /**
     * 接收 Paper 端 BIND_RESULT 回报，直接发送 QQ 消息
     */
    @Subscribe
    fun onPluginMessage(event: PluginMessageEvent) {
        if (event.identifier != whitelistChannel) return

        val msg = String(event.data, Charsets.UTF_8)
        val parts = msg.split("|", limit = 4)
        if (parts.size < 4) return

        val action = parts[0]
        if (action != "BIND_RESULT") return

        val status = parts[1]
        val message = parts[2]
        val code = parts[3]

        val packId = packIdMap.remove(code)
        if (packId != null) {
            ClientManager.postRespone(message, status, packId)
        } else {
            logger.info("QQ response (no packId): $message")
        }
    }

    /**
     * 玩家连入子服时，补发缓存的 BIND 消息
     */
    @Subscribe
    fun onServerPostConnect(event: ServerPostConnectEvent) {
        val server = event.player.currentServer.orElse(null) ?: return
        var msg: String?
        while (pendingBinds.poll().also { msg = it } != null) {
            try {
                server.sendPluginMessage(whitelistChannel, msg!!.toByteArray())
                logger.info("补发缓存 BIND: $msg")
            } catch (e: Exception) {
                logger.warn("补发缓存 BIND 失败: {}", e.message)
            }
        }
    }

    override fun sendCommand(command: String): CompletableFuture<HExecution> {
        val sender = VelocityConsoleSender(this)
        return sender.execute(command)
    }

    fun sendCommandToServer(serverName: String, command: String): CompletableFuture<HExecution> {
        val sender = VelocityConsoleSender(this)
        return sender.executeOnServer(serverName, command)
    }

    override fun submit(task: Runnable): Cancelable {
        val scheduledTask = server.scheduler.buildTask(this, task).schedule()
        return HuHoBotTask(scheduledTask)
    }

    override fun submitLater(delay: Long, task: Runnable): Cancelable {
        val scheduledTask = server.scheduler.buildTask(this, task)
            .delay(delay * 50, TimeUnit.MILLISECONDS)
            .schedule()
        return HuHoBotTask(scheduledTask)
    }

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable {
        val scheduledTask = server.scheduler.buildTask(this, task)
            .delay(delay * 50, TimeUnit.MILLISECONDS)
            .repeat(period * 50, TimeUnit.MILLISECONDS)
            .schedule()
        return HuHoBotTask(scheduledTask)
    }

    override fun getHashKey(): String = configManager.getHashKey()
    override fun getPlatform(): String = "velocity"
    override fun getName(): String = configManager.getName()
    fun getWhiteList(): WhiteList = configManager.getWhiteList()
    override fun getChatFormat(): ChatFormat = configManager.getChatFormat()
    override fun getMotd(): Motd = configManager.getMotd()
    override fun getConfigFile(): File = dataDirectory.resolve("config.yml").toFile()

    override fun addWhiteList(playerName: String) {
        val command = getWhiteList().addCommand.replace("{name}", playerName)
        val finalCommand = if (command.contains(":")) command else "ALL:$command"
        sendCommand(finalCommand)
    }

    override fun delWhiteList(playerName: String) {
        val command = getWhiteList().delCommand.replace("{name}", playerName)
        val finalCommand = if (command.contains(":")) command else "ALL:$command"
        sendCommand(finalCommand)
    }

    override fun getPluginVersion(): String = pluginContainer.description.version.orElse("1.0.0")
    override fun getServerId(): String = configManager.getServerId()
    override fun getCallbackConvertImg(): Int = configManager.getCallbackConvertImg()
    override fun getFilterRegexList(): List<String> = configManager.getFilterRegexList()

    override fun loadCustomCommand() { configManager.loadCommandsFromConfig() }
    override fun setHashKey(hashKey: String) { configManager.setHashKey(hashKey) }
    override fun setServerId(serverId: String) { configManager.setServerId(serverId) }
    override fun log_info(msg: String) { logger.info(msg) }
    override fun log_error(msg: String) { logger.error(msg) }
    override fun log_warning(msg: String) { logger.warn(msg) }

    override fun getQueryOnline(): BaseEvent = QueryOnline(this)
    override fun getQueryAllowList(): BaseEvent = QueryAllowList(this)

    fun reconnectRedis(): Boolean {
        redisManager?.disconnect()
        if (configManager.isRedisEnabled()) {
            redisManager = RedisManager(this)
            redisManager!!.connect(
                configManager.getRedisHost(),
                configManager.getRedisPort(),
                configManager.getRedisPassword()
            )
            return redisManager!!.isConnected()
        }
        return false
    }
}