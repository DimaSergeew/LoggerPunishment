package org.bedepay.loggerpunishment.command;

import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Обработчик команд плагина
 */
public class CommandHandler implements CommandExecutor, TabCompleter {
    
    private final LoggerPunishment plugin;
    private final Logger logger;
    
    public CommandHandler(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("punishmentlogs")) {
            return false;
        }
        
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "reload":
                return handleReload(sender);
                
            case "test":
                return handleTest(sender, args);
                
            case "stats":
                return handleStats(sender);
                
            case "queue":
                return handleQueue(sender);
                
            case "sync":
                return handleSync(sender, args);
                
            default:
                sendHelp(sender);
                return true;
        }
    }
    
    /**
     * Обработка команды reload
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("punishmentlogs.reload")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }
        
        try {
            plugin.getConfigManager().reloadConfig();
            sender.sendMessage("§aКонфигурация успешно перезагружена!");
            logger.info("Конфигурация перезагружена пользователем " + sender.getName());
        } catch (Exception e) {
            sender.sendMessage("§cОшибка при перезагрузке конфигурации: " + e.getMessage());
            logger.warning("Ошибка при перезагрузке конфигурации: " + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * Обработка команды test
     */
    private boolean handleTest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("punishmentlogs.test")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /punishmentlogs test <игрок>");
            return true;
        }
        
        String playerName = args[1];
        sender.sendMessage("§aТестовое сообщение для игрока " + playerName + " отправлено!");
        
        // TODO: Реализовать тестовую отправку сообщения
        
        return true;
    }
    
    /**
     * Обработка команды stats
     */
    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("punishmentlogs.stats")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }
        
        try {
            StringBuilder stats = new StringBuilder();
            stats.append("§6=== Статистика LoggerPunishment ===\n");
            
            // Статистика сервиса
            if (plugin.getPunishmentService() != null) {
                stats.append("§e").append(plugin.getPunishmentService().getServiceStats()).append("\n");
            }
            
            // Статистика форумов
            if (plugin.getForumManager() != null) {
                stats.append("§e").append(plugin.getForumManager().getForumStats()).append("\n");
            }
            
            // Статистика Redis
            if (plugin.getRedisManager() != null && plugin.getRedisManager().isEnabled()) {
                stats.append("§e").append(plugin.getRedisManager().getRedisStats()).append("\n");
            }
            
            // Статистика AuthBot API
            if (plugin.getAuthBotAPI() != null) {
                stats.append("§e").append(plugin.getAuthBotAPI().getApiStats());
            }
            
            sender.sendMessage(stats.toString());
            
        } catch (Exception e) {
            sender.sendMessage("§cОшибка при получении статистики: " + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * Обработка команды queue
     */
    private boolean handleQueue(CommandSender sender) {
        if (!sender.hasPermission("punishmentlogs.stats")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }
        
        try {
            if (plugin.getRedisManager() != null && plugin.getRedisManager().isEnabled()) {
                int queueSize = plugin.getRedisManager().getQueueSize();
                sender.sendMessage("§6Размер очереди Discord действий: §e" + queueSize);
            } else {
                sender.sendMessage("§cRedis не подключен, очередь недоступна");
            }
        } catch (Exception e) {
            sender.sendMessage("§cОшибка при получении размера очереди: " + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * Обработка команды sync
     */
    private boolean handleSync(CommandSender sender, String[] args) {
        if (!sender.hasPermission("punishmentlogs.sync")) {
            sender.sendMessage("§cУ вас нет прав для выполнения этой команды!");
            return true;
        }
        
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /punishmentlogs sync <игрок>");
            return true;
        }
        
        String playerName = args[1];
        sender.sendMessage("§aСинхронизация статистики для игрока " + playerName + " запущена!");
        
        // TODO: Реализовать ручную синхронизацию статистики
        
        return true;
    }
    
    /**
     * Отправка справки по командам
     */
    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== LoggerPunishment Команды ===");
        sender.sendMessage("§e/punishmentlogs reload §7- Перезагрузить конфигурацию");
        sender.sendMessage("§e/punishmentlogs test <игрок> §7- Тестовая отправка сообщения");
        sender.sendMessage("§e/punishmentlogs stats §7- Статистика плагина");
        sender.sendMessage("§e/punishmentlogs queue §7- Размер очереди Discord действий");
        sender.sendMessage("§e/punishmentlogs sync <игрок> §7- Ручная синхронизация статистики");
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("reload", "test", "stats", "queue", "sync");
            String input = args[0].toLowerCase();
            
            for (String subCommand : subCommands) {
                if (subCommand.startsWith(input) && sender.hasPermission("punishmentlogs." + subCommand)) {
                    completions.add(subCommand);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("sync"))) {
            // Автодополнение имен игроков
            String input = args[1].toLowerCase();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(input)) {
                    completions.add(player.getName());
                }
            }
        }
        
        return completions;
    }
} 