package org.bedepay.loggerpunishment;

import org.bedepay.loggerpunishment.api.AuthBotAPI;
import org.bedepay.loggerpunishment.command.CommandHandler;
import org.bedepay.loggerpunishment.config.ConfigManager;
import org.bedepay.loggerpunishment.database.DatabaseManager;
import org.bedepay.loggerpunishment.discord.DiscordManager;
import org.bedepay.loggerpunishment.discord.ForumManager;
import org.bedepay.loggerpunishment.discord.MessageFormatter;
import org.bedepay.loggerpunishment.listener.PunishmentListener;
import org.bedepay.loggerpunishment.redis.RedisManager;
import org.bedepay.loggerpunishment.service.PunishmentService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

public final class LoggerPunishment extends JavaPlugin {
    
    private static LoggerPunishment instance;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private RedisManager redisManager;
    private AuthBotAPI authBotAPI;
    
    // Менеджеры компонентов
    private DiscordManager discordManager;
    private ForumManager forumManager;
    private MessageFormatter messageFormatter;
    private PunishmentListener punishmentListener;
    private PunishmentService punishmentService;
    private CommandHandler commandHandler;
    
    // Менеджеры компонентов (будут инициализированы позже)
    // private DiscordManager discordManager;
    // private PunishmentListener punishmentListener;
    // private CommandHandler commandHandler;
    
    @Override
    public void onEnable() {
        instance = this;
        
        try {
            // Логирование запуска
            getLogger().info("Запуск плагина LoggerPunishment v" + getDescription().getVersion());
            
            // Инициализация менеджера конфигурации
            initializeConfig();
            
            // Инициализация базы данных
            initializeDatabase();
            
            // Инициализация Redis (опционально)
            initializeRedis();
            
            // Инициализация AuthBot API
            initializeAuthBotAPI();
            
            // Инициализация Discord
            initializeDiscord();
            
            // Инициализация сервисов и слушателей
            initializeServices();
            
            // Регистрация событий
            registerListeners();
            
            // Запуск задач
            startScheduledTasks();
            
            getLogger().info("Плагин LoggerPunishment успешно запущен!");
            
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Критическая ошибка при запуске плагина: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }
    
    @Override
    public void onDisable() {
        try {
            getLogger().info("Остановка плагина LoggerPunishment...");
            
            // Graceful shutdown всех компонентов
            if (discordManager != null) {
                discordManager.shutdown();
            }
            
            if (authBotAPI != null) {
                authBotAPI.shutdown();
            }
            
            if (redisManager != null) {
                redisManager.shutdown();
            }
            
            if (databaseManager != null) {
                databaseManager.shutdown();
            }
            
            getLogger().info("Плагин LoggerPunishment остановлен");
            
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Ошибка при остановке плагина: " + e.getMessage(), e);
        } finally {
            instance = null;
        }
    }
    
    /**
     * Инициализация менеджера конфигурации
     */
    private void initializeConfig() {
        try {
            getLogger().info("Загрузка конфигурации...");
            configManager = new ConfigManager(this);
            getLogger().info("Конфигурация загружена успешно");
        } catch (Exception e) {
            throw new RuntimeException("Не удалось загрузить конфигурацию", e);
        }
    }
    
    /**
     * Инициализация менеджера базы данных
     */
    private void initializeDatabase() {
        try {
            getLogger().info("Инициализация базы данных...");
            databaseManager = new DatabaseManager(this);
            databaseManager.initialize();
            getLogger().info("База данных инициализирована успешно");
        } catch (Exception e) {
            throw new RuntimeException("Не удалось инициализировать базу данных", e);
        }
    }
    
    /**
     * Инициализация менеджера Redis
     */
    private void initializeRedis() {
        try {
            getLogger().info("Инициализация Redis...");
            redisManager = new RedisManager(this);
            redisManager.initialize();
            
            if (redisManager.isEnabled()) {
                getLogger().info("Redis инициализирован успешно");
            } else {
                getLogger().info("Redis не настроен или недоступен, продолжаем без кэширования");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Ошибка при инициализации Redis: " + e.getMessage());
            getLogger().info("Продолжаем работу без Redis");
        }
    }
    
    /**
     * Инициализация AuthBot API
     */
    private void initializeAuthBotAPI() {
        try {
            getLogger().info("Инициализация AuthBot API...");
            authBotAPI = new AuthBotAPI(this);
            
            // Проверка доступности API
            if (authBotAPI.isApiAvailable()) {
                getLogger().info("AuthBot API инициализирован и доступен");
            } else {
                getLogger().warning("AuthBot API недоступен, Discord ID игроков не будет получен");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Ошибка при инициализации AuthBot API: " + e.getMessage());
            getLogger().warning("Продолжаем работу без получения Discord ID");
        }
    }
    
    /**
     * Получить экземпляр плагина
     */
    public static LoggerPunishment getInstance() {
        return instance;
    }
    
    /**
     * Получить менеджер конфигурации
     */
    public ConfigManager getConfigManager() {
        return configManager;
    }
    
    /**
     * Получить менеджер базы данных
     */
    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
    
    /**
     * Получить менеджер Redis
     */
    public RedisManager getRedisManager() {
        return redisManager;
    }
    
    /**
     * Получить AuthBot API
     */
    public AuthBotAPI getAuthBotAPI() {
        return authBotAPI;
    }
    
    /**
     * Инициализация Discord менеджера
     */
    private void initializeDiscord() {
        try {
            getLogger().info("Инициализация Discord...");
            discordManager = new DiscordManager(this);
            discordManager.initialize();
            
            if (discordManager.isEnabled()) {
                // Инициализация ForumManager после DiscordManager
                forumManager = new ForumManager(this, discordManager.getJDA());
                getLogger().info("Discord инициализирован успешно");
            } else {
                getLogger().warning("Discord недоступен, уведомления отключены");
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Ошибка при инициализации Discord: " + e.getMessage());
        }
    }
    
    /**
     * Инициализация сервисов
     */
    private void initializeServices() {
        getLogger().info("Инициализация сервисов...");
        punishmentService = new PunishmentService(this);
        punishmentListener = new PunishmentListener(this);
        commandHandler = new CommandHandler(this);
        messageFormatter = new MessageFormatter(this);
        getLogger().info("Сервисы инициализированы");
    }
    
    /**
     * Регистрация событий
     */
    private void registerListeners() {
        getLogger().info("Регистрация событий...");
        
        // PunishmentListener теперь регистрирует свои события самостоятельно
        // через рефлексию для LiteBans и внутренний класс для CMI
        
        // Регистрация команд
        getCommand("punishmentlogs").setExecutor(commandHandler);
        getCommand("punishmentlogs").setTabCompleter(commandHandler);
        
        getLogger().info("События зарегистрированы");
    }
    
    /**
     * Запуск периодических задач
     */
    private void startScheduledTasks() {
        getLogger().info("Запуск периодических задач...");
        
        // Проверка истекших наказаний каждые 5 минут
        new BukkitRunnable() {
            @Override
            public void run() {
                // TODO: Добавить метод checkExpiredPunishments в PunishmentService
                // if (punishmentService != null) {
                //     punishmentService.checkExpiredPunishments();
                // }
            }
        }.runTaskTimerAsynchronously(this, 20L * 60L * 5L, 20L * 60L * 5L);
        
        getLogger().info("Периодические задачи запущены");
    }
    
    /**
     * Получить Discord менеджер
     */
    public DiscordManager getDiscordManager() {
        return discordManager;
    }
    
    /**
     * Получить сервис наказаний
     */
    public PunishmentService getPunishmentService() {
        return punishmentService;
    }
    
    /**
     * Получить форум менеджер
     */
    public ForumManager getForumManager() {
        return forumManager;
    }
    
    /**
     * Получить CommandHandler
     */
    public CommandHandler getCommandHandler() {
        return commandHandler;
    }
    
    /**
     * Получить MessageFormatter
     */
    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }
    
    // TODO: Добавить геттеры для остальных менеджеров
    // public DiscordManager getDiscordManager() { return discordManager; }
}
