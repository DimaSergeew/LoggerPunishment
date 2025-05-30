package org.bedepay.loggerpunishment.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Менеджер конфигурации плагина
 */
public class ConfigManager {
    private final JavaPlugin plugin;
    private final Logger logger;
    private YamlConfiguration config;
    private File configFile;
    
    // Кэшированные значения для быстрого доступа
    private DatabaseConfig databaseConfig;
    private DiscordConfig discordConfig;
    private RedisConfig redisConfig;
    private AuthBotConfig authBotConfig;
    private PluginSettings pluginSettings;
    private IntegrationSettings integrationSettings;
    private CacheSettings cacheSettings;
    
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        
        loadConfig();
    }
    
    /**
     * Загрузить конфигурацию из файла
     */
    public void loadConfig() {
        try {
            // Создать папку плагина если не существует
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            
            // Создать файл конфигурации если не существует
            if (!configFile.exists()) {
                plugin.saveDefaultConfig();
            }
            
            config = YamlConfiguration.loadConfiguration(configFile);
            
            // Загрузить все секции конфигурации
            loadDatabaseConfig();
            loadDiscordConfig();
            loadRedisConfig();
            loadAuthBotConfig();
            loadPluginSettings();
            loadIntegrationSettings();
            loadCacheSettings();
            
            logger.info("Конфигурация успешно загружена");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при загрузке конфигурации: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось загрузить конфигурацию", e);
        }
    }
    
    /**
     * Сохранить конфигурацию в файл
     */
    public void saveConfig() {
        try {
            config.save(configFile);
            logger.info("Конфигурация сохранена");
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Ошибка при сохранении конфигурации: " + e.getMessage(), e);
        }
    }
    
    /**
     * Перезагрузить конфигурацию
     */
    public void reloadConfig() {
        try {
            plugin.reloadConfig();
            loadConfig();
            logger.info("Конфигурация успешно перезагружена");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при перезагрузке конфигурации: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось перезагрузить конфигурацию", e);
        }
    }
    
    private void loadDatabaseConfig() {
        ConfigurationSection db = config.getConfigurationSection("database");
        if (db == null) {
            throw new RuntimeException("Секция 'database' не найдена в конфигурации");
        }
        
        databaseConfig = new DatabaseConfig();
        databaseConfig.type = db.getString("type", "sqlite");
        
        // SQLite настройки
        ConfigurationSection sqlite = db.getConfigurationSection("sqlite");
        if (sqlite != null) {
            databaseConfig.sqliteFile = sqlite.getString("file", "punishment_logs.db");
        }
        
        // MySQL настройки
        ConfigurationSection mysql = db.getConfigurationSection("mysql");
        if (mysql != null) {
            databaseConfig.mysqlHost = mysql.getString("host", "localhost");
            databaseConfig.mysqlPort = mysql.getInt("port", 3306);
            databaseConfig.mysqlDatabase = mysql.getString("database", "minecraft");
            databaseConfig.mysqlUsername = mysql.getString("username", "user");
            databaseConfig.mysqlPassword = mysql.getString("password", "password");
        }
        
        // Настройки пула соединений
        ConfigurationSection pool = db.getConfigurationSection("pool");
        if (pool != null) {
            databaseConfig.maximumPoolSize = pool.getInt("maximum_pool_size", 10);
            databaseConfig.minimumIdle = pool.getInt("minimum_idle", 2);
            databaseConfig.connectionTimeout = pool.getLong("connection_timeout", 30000);
            databaseConfig.idleTimeout = pool.getLong("idle_timeout", 600000);
            databaseConfig.maxLifetime = pool.getLong("max_lifetime", 1800000);
        }
    }
    
    private void loadDiscordConfig() {
        ConfigurationSection discord = config.getConfigurationSection("discord");
        if (discord == null) {
            throw new RuntimeException("Секция 'discord' не найдена в конфигурации");
        }
        
        discordConfig = new DiscordConfig();
        discordConfig.token = discord.getString("token", "YOUR_DISCORD_BOT_TOKEN");
        discordConfig.guildId = discord.getLong("guild_id", 0);
        
        // Каналы
        ConfigurationSection channels = discord.getConfigurationSection("channels");
        if (channels != null) {
            discordConfig.playersForumId = channels.getLong("players_forum", 0);
            discordConfig.moderatorsForumId = channels.getLong("moderators_forum", 0);
            discordConfig.logChannelId = channels.getLong("log_channel", 0);
            discordConfig.noLinkChannelId = channels.getLong("no_link_notifications", 0);
        }
        
        // Сообщения
        loadDiscordMessages(discord);
    }
    
    private void loadDiscordMessages(ConfigurationSection discord) {
        ConfigurationSection messages = discord.getConfigurationSection("messages");
        if (messages == null) return;
        
        // Заголовки наказаний
        ConfigurationSection titles = messages.getConfigurationSection("punishment_titles");
        if (titles != null) {
            discordConfig.punishmentTitles = new HashMap<>();
            for (String key : titles.getKeys(false)) {
                discordConfig.punishmentTitles.put(key, titles.getString(key));
            }
        }
        
        // Заголовки снятия наказаний
        ConfigurationSection unbanTitles = messages.getConfigurationSection("unban_titles");
        if (unbanTitles != null) {
            discordConfig.unbanTitles = new HashMap<>();
            for (String key : unbanTitles.getKeys(false)) {
                discordConfig.unbanTitles.put(key, unbanTitles.getString(key));
            }
        }
        
        // Цвета
        ConfigurationSection colors = messages.getConfigurationSection("colors");
        if (colors != null) {
            discordConfig.colors = new HashMap<>();
            for (String key : colors.getKeys(false)) {
                String colorHex = colors.getString(key);
                try {
                    Color color = Color.decode(colorHex);
                    discordConfig.colors.put(key, color);
                } catch (NumberFormatException e) {
                    logger.warning("Неверный формат цвета для ключа " + key + ": " + colorHex);
                }
            }
        }
        
        // Эмодзи
        ConfigurationSection emojis = messages.getConfigurationSection("emojis");
        if (emojis != null) {
            discordConfig.emojis = new HashMap<>();
            for (String key : emojis.getKeys(false)) {
                discordConfig.emojis.put(key, emojis.getString(key));
            }
        }
        
        // Шаблоны
        ConfigurationSection templates = messages.getConfigurationSection("templates");
        if (templates != null) {
            discordConfig.noDiscordLinkTemplate = templates.getString("no_discord_link", "");
        }
    }
    
    private void loadRedisConfig() {
        ConfigurationSection redis = config.getConfigurationSection("redis");
        if (redis == null) {
            // Redis опционален, создаем конфиг по умолчанию
            redisConfig = new RedisConfig();
            return;
        }
        
        redisConfig = new RedisConfig();
        redisConfig.host = redis.getString("host", "localhost");
        redisConfig.port = redis.getInt("port", 6379);
        redisConfig.password = redis.getString("password", "");
        redisConfig.database = redis.getInt("database", 0);
        redisConfig.timeout = redis.getInt("timeout", 3000);
        
        ConfigurationSection pool = redis.getConfigurationSection("pool");
        if (pool != null) {
            redisConfig.maxActive = pool.getInt("max_active", 20);
            redisConfig.maxIdle = pool.getInt("max_idle", 10);
            redisConfig.minIdle = pool.getInt("min_idle", 5);
        }
    }
    
    private void loadAuthBotConfig() {
        ConfigurationSection authBot = config.getConfigurationSection("auth_bot");
        if (authBot == null) {
            throw new RuntimeException("Секция 'auth_bot' не найдена в конфигурации");
        }
        
        authBotConfig = new AuthBotConfig();
        authBotConfig.apiUrl = authBot.getString("api_url", "http://localhost:8080/api/v1/discord-link");
        authBotConfig.timeout = authBot.getInt("timeout", 5000);
        authBotConfig.retryAttempts = authBot.getInt("retry_attempts", 3);
        authBotConfig.retryDelay = authBot.getInt("retry_delay", 1000);
    }
    
    private void loadPluginSettings() {
        ConfigurationSection settings = config.getConfigurationSection("settings");
        if (settings == null) {
            pluginSettings = new PluginSettings(); // Значения по умолчанию
            return;
        }
        
        pluginSettings = new PluginSettings();
        pluginSettings.statsUpdateInterval = settings.getInt("stats_update_interval", 30);
        pluginSettings.messageCleanupDelay = settings.getInt("message_cleanup_delay", 2);
        pluginSettings.maxQueueSize = settings.getInt("max_queue_size", 1000);
        pluginSettings.queueMessageRetention = settings.getInt("queue_message_retention", 24);
        pluginSettings.discordReconnectInterval = settings.getInt("discord_reconnect_interval", 60);
        pluginSettings.maxSendAttempts = settings.getInt("max_send_attempts", 3);
        pluginSettings.sendRetryDelay = settings.getInt("send_retry_delay", 2000);
        pluginSettings.debugMode = settings.getBoolean("debug_mode", false);
        pluginSettings.verboseLogging = settings.getBoolean("verbose_logging", true);
        pluginSettings.autoBackup = settings.getBoolean("auto_backup", true);
        pluginSettings.backupIntervalHours = settings.getInt("backup_interval_hours", 24);
    }
    
    private void loadIntegrationSettings() {
        ConfigurationSection integrations = config.getConfigurationSection("integrations");
        if (integrations == null) {
            integrationSettings = new IntegrationSettings(); // Значения по умолчанию
            return;
        }
        
        integrationSettings = new IntegrationSettings();
        
        // LiteBans настройки
        ConfigurationSection litebans = integrations.getConfigurationSection("litebans");
        if (litebans != null) {
            integrationSettings.trackBans = litebans.getBoolean("track_bans", true);
            integrationSettings.trackMutes = litebans.getBoolean("track_mutes", true);
            integrationSettings.trackKicks = litebans.getBoolean("track_kicks", true);
            integrationSettings.trackWarnings = litebans.getBoolean("track_warnings", false);
            integrationSettings.minTempDuration = litebans.getInt("min_temp_duration", 1);
        }
        
        // CMI настройки
        ConfigurationSection cmi = integrations.getConfigurationSection("cmi");
        if (cmi != null) {
            integrationSettings.trackJails = cmi.getBoolean("track_jails", true);
            integrationSettings.minJailDuration = cmi.getInt("min_jail_duration", 5);
        }
    }
    
    private void loadCacheSettings() {
        ConfigurationSection cache = config.getConfigurationSection("cache");
        if (cache == null) {
            cacheSettings = new CacheSettings(); // Значения по умолчанию
            return;
        }
        
        cacheSettings = new CacheSettings();
        cacheSettings.playerDiscordCacheTtl = cache.getInt("player_discord_cache_ttl", 60);
        cacheSettings.threadCacheTtl = cache.getInt("thread_cache_ttl", 30);
        cacheSettings.permissionsCacheTtl = cache.getInt("permissions_cache_ttl", 5);
        cacheSettings.statsCacheSize = cache.getInt("stats_cache_size", 500);
    }
    
    // Геттеры для доступа к конфигурации
    public DatabaseConfig getDatabaseConfig() { return databaseConfig; }
    public DiscordConfig getDiscordConfig() { return discordConfig; }
    public RedisConfig getRedisConfig() { return redisConfig; }
    public AuthBotConfig getAuthBotConfig() { return authBotConfig; }
    public PluginSettings getPluginSettings() { return pluginSettings; }
    public IntegrationSettings getIntegrationSettings() { return integrationSettings; }
    public CacheSettings getCacheSettings() { return cacheSettings; }
    public YamlConfiguration getRawConfig() { return config; }
    
    // Дополнительные методы для Discord
    public String getDiscordToken() { return discordConfig.token; }
    public long getPlayerChannelId() { return discordConfig.playersForumId; }
    public long getModeratorChannelId() { return discordConfig.moderatorsForumId; }
    public long getLogChannelId() { return discordConfig.logChannelId; }
    public boolean isPlayerNotificationsEnabled() { return discordConfig.playersForumId > 0; }
    public boolean isModeratorNotificationsEnabled() { return discordConfig.moderatorsForumId > 0; }
    public boolean isLogNotificationsEnabled() { return discordConfig.logChannelId > 0; }
    
    // Методы для обновления конфигурации
    public void updateDiscordChannels(long playersForumId, long moderatorsForumId, long logChannelId) {
        config.set("discord.channels.players_forum", playersForumId);
        config.set("discord.channels.moderators_forum", moderatorsForumId);
        config.set("discord.channels.log_channel", logChannelId);
        
        discordConfig.playersForumId = playersForumId;
        discordConfig.moderatorsForumId = moderatorsForumId;
        discordConfig.logChannelId = logChannelId;
        
        saveConfig();
    }
    
    // Вложенные классы для конфигурации
    public static class DatabaseConfig {
        public String type = "sqlite";
        public String sqliteFile = "punishment_logs.db";
        public String mysqlHost = "localhost";
        public int mysqlPort = 3306;
        public String mysqlDatabase = "minecraft";
        public String mysqlUsername = "user";
        public String mysqlPassword = "password";
        public int maximumPoolSize = 10;
        public int minimumIdle = 2;
        public long connectionTimeout = 30000;
        public long idleTimeout = 600000;
        public long maxLifetime = 1800000;
    }
    
    public static class DiscordConfig {
        public String token = "YOUR_DISCORD_BOT_TOKEN";
        public long guildId = 0;
        public long playersForumId = 0;
        public long moderatorsForumId = 0;
        public long logChannelId = 0;
        public long noLinkChannelId = 0;
        public Map<String, String> punishmentTitles = new HashMap<>();
        public Map<String, String> unbanTitles = new HashMap<>();
        public Map<String, Color> colors = new HashMap<>();
        public Map<String, String> emojis = new HashMap<>();
        public String noDiscordLinkTemplate = "";
    }
    
    public static class RedisConfig {
        public String host = "localhost";
        public int port = 6379;
        public String password = "";
        public int database = 0;
        public int timeout = 3000;
        public int maxActive = 20;
        public int maxIdle = 10;
        public int minIdle = 5;
    }
    
    public static class AuthBotConfig {
        public String apiUrl = "http://localhost:8080/api/v1/discord-link";
        public int timeout = 5000;
        public int retryAttempts = 3;
        public int retryDelay = 1000;
    }
    
    public static class PluginSettings {
        public int statsUpdateInterval = 30;
        public int messageCleanupDelay = 2;
        public int maxQueueSize = 1000;
        public int queueMessageRetention = 24;
        public int discordReconnectInterval = 60;
        public int maxSendAttempts = 3;
        public int sendRetryDelay = 2000;
        public boolean debugMode = false;
        public boolean verboseLogging = true;
        public boolean autoBackup = true;
        public int backupIntervalHours = 24;
    }
    
    public static class IntegrationSettings {
        public boolean trackBans = true;
        public boolean trackMutes = true;
        public boolean trackKicks = true;
        public boolean trackWarnings = false;
        public int minTempDuration = 1;
        public boolean trackJails = true;
        public int minJailDuration = 5;
    }
    
    public static class CacheSettings {
        public int playerDiscordCacheTtl = 60;
        public int threadCacheTtl = 30;
        public int permissionsCacheTtl = 5;
        public int statsCacheSize = 500;
    }
} 