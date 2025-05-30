package org.bedepay.loggerpunishment.listener;

import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.config.ConfigManager;
import org.bedepay.loggerpunishment.model.PunishmentType;
import org.bedepay.loggerpunishment.model.UnbanType;
import org.bedepay.loggerpunishment.service.PunishmentService;
import org.bedepay.loggerpunishment.util.TimeFormatter;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Слушатель событий наказаний из LiteBans и CMI
 */
public class PunishmentListener {
    
    private final LoggerPunishment plugin;
    private final PunishmentService punishmentService;
    private final ConfigManager configManager;
    private final Logger logger;
    
    public PunishmentListener(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
        this.configManager = plugin.getConfigManager();
        this.logger = plugin.getLogger();
        
        // Регистрация слушателей LiteBans
        registerLiteBansListeners();
        
        // Регистрация слушателей CMI
        registerCMIListeners();
    }
    
    /**
     * Регистрация слушателей событий LiteBans
     */
    private void registerLiteBansListeners() {
        try {
            // Проверяем наличие LiteBans API
            Class.forName("litebans.api.Events");
            
            ConfigManager.IntegrationSettings integration = configManager.getIntegrationSettings();
            
            // Используем рефлексию для регистрации слушателей LiteBans
            Object eventsInstance = Class.forName("litebans.api.Events").getMethod("get").invoke(null);
            
            // Слушатель банов
            if (integration.trackBans) {
                registerLiteBansEvent(eventsInstance, "BAN", this::handleLiteBansBan);
                registerLiteBansEvent(eventsInstance, "UNBAN", this::handleLiteBansUnban);
            }
            
            // Слушатель мутов
            if (integration.trackMutes) {
                registerLiteBansEvent(eventsInstance, "MUTE", this::handleLiteBansMute);
                registerLiteBansEvent(eventsInstance, "UNMUTE", this::handleLiteBansUnmute);
            }
            
            // Слушатель киков
            if (integration.trackKicks) {
                registerLiteBansEvent(eventsInstance, "KICK", this::handleLiteBansKick);
            }
            
            logger.info("LiteBans слушатели зарегистрированы");
            
        } catch (ClassNotFoundException e) {
            logger.warning("LiteBans API не найден, события LiteBans не будут обрабатываться");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при регистрации LiteBans слушателей: " + e.getMessage(), e);
        }
    }
    
    /**
     * Регистрация события LiteBans через рефлексию
     */
    private void registerLiteBansEvent(Object eventsInstance, String eventType, LiteBansEventHandler handler) {
        try {
            Class<?> eventTypeClass = Class.forName("litebans.api.Events$Type");
            Object eventTypeEnum = Enum.valueOf((Class<Enum>) eventTypeClass, eventType);
            
            // Создаем обработчик события
            Object eventHandler = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{Class.forName("litebans.api.events.LiteBansEvent")},
                (proxy, method, args) -> {
                    if (method.getName().equals("onEvent")) {
                        handler.handle(args[0]);
                    }
                    return null;
                }
            );
            
            // Регистрируем обработчик
            eventsInstance.getClass().getMethod("register", eventTypeClass, Class.forName("litebans.api.events.LiteBansEvent"))
                .invoke(eventsInstance, eventTypeEnum, eventHandler);
                
        } catch (Exception e) {
            logger.warning("Не удалось зарегистрировать LiteBans событие " + eventType + ": " + e.getMessage());
        }
    }
    
    /**
     * Интерфейс для обработчиков событий LiteBans
     */
    @FunctionalInterface
    private interface LiteBansEventHandler {
        void handle(Object event);
    }
    
    /**
     * Обработка бана из LiteBans
     */
    private void handleLiteBansBan(Object event) {
        try {
            // Извлекаем данные через рефлексию
            String uuid = (String) event.getClass().getMethod("getUuid").invoke(event);
            String name = (String) event.getClass().getMethod("getName").invoke(event);
            String executorUUID = (String) event.getClass().getMethod("getExecutorUUID").invoke(event);
            String executor = (String) event.getClass().getMethod("getExecutor").invoke(event);
            String reason = (String) event.getClass().getMethod("getReason").invoke(event);
            long duration = (Long) event.getClass().getMethod("getDuration").invoke(event);
            long id = (Long) event.getClass().getMethod("getId").invoke(event);
            
            // Проверка минимальной длительности для временных банов
            if (duration > 0) {
                long durationMinutes = TimeUnit.MILLISECONDS.toMinutes(duration);
                if (durationMinutes < configManager.getIntegrationSettings().minTempDuration) {
                    return; // Игнорируем слишком короткие баны
                }
            }
            
            UUID playerUuid = parseUUID(uuid);
            UUID moderatorUuid = parseUUID(executorUUID);
            
            String playerName = name;
            String moderatorName = executor;
            Long durationSeconds = duration > 0 ? TimeFormatter.millisecondsToSeconds(duration) : null;
            String punishmentId = String.valueOf(id);
            
            // Асинхронная обработка
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                punishmentService.processPunishment(
                    PunishmentType.BAN,
                    playerUuid,
                    playerName,
                    moderatorUuid,
                    moderatorName,
                    reason,
                    durationSeconds,
                    punishmentId
                );
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке бана LiteBans: " + e.getMessage(), e);
        }
    }
    
    /**
     * Обработка разбана из LiteBans
     */
    private void handleLiteBansUnban(Object event) {
        try {
            String executorUUID = (String) event.getClass().getMethod("getExecutorUUID").invoke(event);
            String executor = (String) event.getClass().getMethod("getExecutor").invoke(event);
            String reason = (String) event.getClass().getMethod("getReason").invoke(event);
            long id = (Long) event.getClass().getMethod("getId").invoke(event);
            
            UUID unbanModeratorUuid = parseUUID(executorUUID);
            String unbanModeratorName = executor;
            String unbanReason = reason;
            String punishmentId = String.valueOf(id);
            
            UnbanType unbanType = determineUnbanType(unbanReason);
            
            // Асинхронная обработка
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                punishmentService.processUnban(
                    punishmentId,
                    unbanModeratorUuid,
                    unbanModeratorName,
                    unbanReason,
                    unbanType
                );
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке разбана LiteBans: " + e.getMessage(), e);
        }
    }
    
    /**
     * Обработка мута из LiteBans
     */
    private void handleLiteBansMute(Object event) {
        try {
            String uuid = (String) event.getClass().getMethod("getUuid").invoke(event);
            String name = (String) event.getClass().getMethod("getName").invoke(event);
            String executorUUID = (String) event.getClass().getMethod("getExecutorUUID").invoke(event);
            String executor = (String) event.getClass().getMethod("getExecutor").invoke(event);
            String reason = (String) event.getClass().getMethod("getReason").invoke(event);
            long duration = (Long) event.getClass().getMethod("getDuration").invoke(event);
            long id = (Long) event.getClass().getMethod("getId").invoke(event);
            
            // Проверка минимальной длительности для временных мутов
            if (duration > 0) {
                long durationMinutes = TimeUnit.MILLISECONDS.toMinutes(duration);
                if (durationMinutes < configManager.getIntegrationSettings().minTempDuration) {
                    return; // Игнорируем слишком короткие муты
                }
            }
            
            UUID playerUuid = parseUUID(uuid);
            UUID moderatorUuid = parseUUID(executorUUID);
            
            String playerName = name;
            String moderatorName = executor;
            Long durationSeconds = duration > 0 ? TimeFormatter.millisecondsToSeconds(duration) : null;
            String punishmentId = String.valueOf(id);
            
            // Асинхронная обработка
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                punishmentService.processPunishment(
                    PunishmentType.MUTE,
                    playerUuid,
                    playerName,
                    moderatorUuid,
                    moderatorName,
                    reason,
                    durationSeconds,
                    punishmentId
                );
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке мута LiteBans: " + e.getMessage(), e);
        }
    }
    
    /**
     * Обработка размута из LiteBans
     */
    private void handleLiteBansUnmute(Object event) {
        try {
            String executorUUID = (String) event.getClass().getMethod("getExecutorUUID").invoke(event);
            String executor = (String) event.getClass().getMethod("getExecutor").invoke(event);
            String reason = (String) event.getClass().getMethod("getReason").invoke(event);
            long id = (Long) event.getClass().getMethod("getId").invoke(event);
            
            UUID unbanModeratorUuid = parseUUID(executorUUID);
            String unbanModeratorName = executor;
            String unbanReason = reason;
            String punishmentId = String.valueOf(id);
            
            UnbanType unbanType = determineUnbanType(unbanReason);
            
            // Асинхронная обработка
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                punishmentService.processUnban(
                    punishmentId,
                    unbanModeratorUuid,
                    unbanModeratorName,
                    unbanReason,
                    unbanType
                );
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке размута LiteBans: " + e.getMessage(), e);
        }
    }
    
    /**
     * Обработка кика из LiteBans
     */
    private void handleLiteBansKick(Object event) {
        try {
            String uuid = (String) event.getClass().getMethod("getUuid").invoke(event);
            String name = (String) event.getClass().getMethod("getName").invoke(event);
            String executorUUID = (String) event.getClass().getMethod("getExecutorUUID").invoke(event);
            String executor = (String) event.getClass().getMethod("getExecutor").invoke(event);
            String reason = (String) event.getClass().getMethod("getReason").invoke(event);
            long id = (Long) event.getClass().getMethod("getId").invoke(event);
            
            UUID playerUuid = parseUUID(uuid);
            UUID moderatorUuid = parseUUID(executorUUID);
            
            String playerName = name;
            String moderatorName = executor;
            String punishmentId = String.valueOf(id);
            
            // Асинхронная обработка
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                punishmentService.processPunishment(
                    PunishmentType.KICK,
                    playerUuid,
                    playerName,
                    moderatorUuid,
                    moderatorName,
                    reason,
                    null, // Кики не имеют длительности
                    punishmentId
                );
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке кика LiteBans: " + e.getMessage(), e);
        }
    }
    
    // ==================== CMI СОБЫТИЯ ====================
    
    /**
     * Регистрация слушателей событий CMI
     */
    private void registerCMIListeners() {
        try {
            // Проверяем наличие CMI API
            Class.forName("com.Zrips.CMI.events.CMIPlayerJailEvent");
            
            ConfigManager.IntegrationSettings integration = configManager.getIntegrationSettings();
            
            if (integration.trackJails) {
                // Создаем слушатель для конкретных CMI событий
                CMIEventListener listener = new CMIEventListener();
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
                logger.info("CMI слушатели зарегистрированы");
            }
            
        } catch (ClassNotFoundException e) {
            logger.warning("CMI API не найден, события CMI не будут обрабатываться");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при регистрации CMI слушателей: " + e.getMessage(), e);
        }
    }
    
    /**
     * Внутренний класс для обработки CMI событий
     */
    private class CMIEventListener implements org.bukkit.event.Listener {
        
        // Используем рефлексию для обработки событий CMI без прямой зависимости
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onPlayerJail(org.bukkit.event.player.PlayerEvent event) {
            try {
                if (!configManager.getIntegrationSettings().trackJails) {
                    return;
                }
                
                // Проверяем тип события через рефлексию
                String eventClassName = event.getClass().getSimpleName();
                
                if (eventClassName.equals("CMIPlayerJailEvent")) {
                    handleCMIJailEvent(event);
                } else if (eventClassName.equals("CMIPlayerUnJailEvent")) {
                    handleCMIUnjailEvent(event);
                }
                
            } catch (Exception e) {
                logger.log(Level.WARNING, "Ошибка при обработке CMI события: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Обработка заключения в тюрьму CMI
     */
    private void handleCMIJailEvent(org.bukkit.event.Event event) {
        try {
            // Используем рефлексию для получения данных из CMI события
            Object player = event.getClass().getMethod("getPlayer").invoke(event);
            Object jail = event.getClass().getMethod("getJail").invoke(event);
            Object executor = event.getClass().getMethod("getExecutor").invoke(event);
            
            UUID playerUuid = (UUID) player.getClass().getMethod("getUniqueId").invoke(player);
            String playerName = (String) player.getClass().getMethod("getName").invoke(player);
            String jailName = (String) jail.getClass().getMethod("getName").invoke(jail);
            
            UUID moderatorUuid = null;
            String moderatorName = "Система";
            
            if (executor != null) {
                moderatorUuid = (UUID) executor.getClass().getMethod("getUniqueId").invoke(executor);
                moderatorName = (String) executor.getClass().getMethod("getName").invoke(executor);
            }
            
            Long duration = null;
            try {
                Object time = jail.getClass().getMethod("getTime").invoke(jail);
                if (time != null) {
                    duration = (Long) time;
                    
                    // Проверка минимальной длительности
                    long durationMinutes = TimeUnit.MILLISECONDS.toMinutes(duration);
                    if (durationMinutes < configManager.getIntegrationSettings().minJailDuration) {
                        return;
                    }
                    
                    duration = TimeFormatter.millisecondsToSeconds(duration);
                }
            } catch (Exception ignored) {
                // Время может быть не установлено
            }
            
            String reason = "Заключение в тюрьму: " + jailName;
            String punishmentId = "cmi_jail_" + playerUuid + "_" + System.currentTimeMillis();
            
            // Асинхронная обработка
            final UUID finalPlayerUuid = playerUuid;
            final UUID finalModeratorUuid = moderatorUuid;
            final String finalPlayerName = playerName;
            final String finalModeratorName = moderatorName;
            final String finalReason = reason;
            final Long finalDuration = duration;
            final String finalPunishmentId = punishmentId;
            
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    punishmentService.processPunishment(
                        PunishmentType.JAIL,
                        finalPlayerUuid,
                        finalPlayerName,
                        finalModeratorUuid,
                        finalModeratorName,
                        finalReason,
                        finalDuration,
                        finalPunishmentId
                    );
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Ошибка при обработке CMI jail: " + e.getMessage(), e);
                }
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке CMI jail события: " + e.getMessage(), e);
        }
    }
    
    /**
     * Обработка освобождения из тюрьмы CMI
     */
    private void handleCMIUnjailEvent(org.bukkit.event.Event event) {
        try {
            // Используем рефлексию для получения данных из CMI события
            Object player = event.getClass().getMethod("getPlayer").invoke(event);
            Object executor = event.getClass().getMethod("getExecutor").invoke(event);
            
            UUID playerUuid = (UUID) player.getClass().getMethod("getUniqueId").invoke(player);
            
            UUID moderatorUuid = null;
            String moderatorName = "Система";
            
            if (executor != null) {
                moderatorUuid = (UUID) executor.getClass().getMethod("getUniqueId").invoke(executor);
                moderatorName = (String) executor.getClass().getMethod("getName").invoke(executor);
            }
            
            String unbanReason = "Освобождение из тюрьмы";
            String punishmentId = "cmi_jail_" + playerUuid + "_*"; // Поиск по паттерну
            
            UnbanType unbanType = moderatorUuid != null ? UnbanType.MANUAL : UnbanType.AUTOMATIC;
            
            // Асинхронная обработка
            final UUID finalModeratorUuid = moderatorUuid;
            final String finalModeratorName = moderatorName;
            
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    punishmentService.processUnban(
                        punishmentId,
                        finalModeratorUuid,
                        finalModeratorName,
                        unbanReason,
                        unbanType
                    );
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Ошибка при обработке CMI unjail: " + e.getMessage(), e);
                }
            });
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при обработке CMI unjail события: " + e.getMessage(), e);
        }
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    /**
     * Безопасный парсинг UUID
     */
    private UUID parseUUID(String uuidString) {
        if (uuidString == null || uuidString.isEmpty() || uuidString.equals("CONSOLE")) {
            return null;
        }
        
        try {
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            logger.warning("Неверный формат UUID: " + uuidString);
            return null;
        }
    }
    
    /**
     * Определить тип снятия наказания по причине
     */
    private UnbanType determineUnbanType(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return UnbanType.EXPIRED;
        }
        
        String lowerReason = reason.toLowerCase();
        if (lowerReason.contains("expired") || lowerReason.contains("истек")) {
            return UnbanType.EXPIRED;
        }
        
        if (lowerReason.contains("automatic") || lowerReason.contains("автоматически")) {
            return UnbanType.AUTOMATIC;
        }
        
        return UnbanType.MANUAL;
    }
    
    /**
     * Получить статистику слушателя
     */
    public String getListenerStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("🎧 PunishmentListener статистика:\n");
        
        ConfigManager.IntegrationSettings integration = configManager.getIntegrationSettings();
        stats.append("📋 Отслеживаемые события:\n");
        
        if (integration.trackBans) {
            stats.append("  ✅ Баны (LiteBans)\n");
        }
        if (integration.trackMutes) {
            stats.append("  ✅ Муты (LiteBans)\n");
        }
        if (integration.trackKicks) {
            stats.append("  ✅ Кики (LiteBans)\n");
        }
        if (integration.trackJails) {
            stats.append("  ✅ Тюрьма (CMI)\n");
        }
        
        stats.append("⚙️ Настройки:\n");
        stats.append("  • Мин. длительность: ").append(integration.minTempDuration).append(" мин.\n");
        stats.append("  • Мин. время в тюрьме: ").append(integration.minJailDuration).append(" мин.");
        
        return stats.toString();
    }
} 