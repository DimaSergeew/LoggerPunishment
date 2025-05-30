package org.bedepay.loggerpunishment.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bedepay.loggerpunishment.LoggerPunishment;
import org.bedepay.loggerpunishment.config.ConfigManager;
import org.bedepay.loggerpunishment.model.ModeratorData;
import org.bedepay.loggerpunishment.model.PlayerData;
import org.bedepay.loggerpunishment.model.PunishmentData;
import org.bedepay.loggerpunishment.model.PunishmentType;
import org.bedepay.loggerpunishment.model.UnbanType;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Менеджер базы данных с поддержкой SQLite и MySQL
 */
public class DatabaseManager {
    
    private final LoggerPunishment plugin;
    private final Logger logger;
    private final ConfigManager configManager;
    private HikariDataSource dataSource;
    
    public DatabaseManager(LoggerPunishment plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configManager = plugin.getConfigManager();
    }
    
    /**
     * Инициализация подключения к базе данных
     */
    public void initialize() {
        try {
            logger.info("Инициализация подключения к базе данных...");
            
            ConfigManager.DatabaseConfig dbConfig = configManager.getDatabaseConfig();
            
            // Создание конфигурации HikariCP
            HikariConfig config = new HikariConfig();
            
            if ("sqlite".equalsIgnoreCase(dbConfig.type)) {
                setupSQLite(config, dbConfig);
            } else if ("mysql".equalsIgnoreCase(dbConfig.type)) {
                setupMySQL(config, dbConfig);
            } else {
                throw new IllegalArgumentException("Неподдерживаемый тип базы данных: " + dbConfig.type);
            }
            
            // Общие настройки пула соединений
            config.setMaximumPoolSize(dbConfig.maximumPoolSize);
            config.setMinimumIdle(dbConfig.minimumIdle);
            config.setConnectionTimeout(dbConfig.connectionTimeout);
            config.setIdleTimeout(dbConfig.idleTimeout);
            config.setMaxLifetime(dbConfig.maxLifetime);
            config.setLeakDetectionThreshold(60000); // 60 секунд
            
            // Создание пула соединений
            dataSource = new HikariDataSource(config);
            
            // Проверка подключения
            testConnection();
            
            // Выполнение миграций
            runMigrations();
            
            logger.info("База данных успешно инициализирована (" + dbConfig.type.toUpperCase() + ")");
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при инициализации базы данных: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось инициализировать базу данных", e);
        }
    }
    
    /**
     * Настройка подключения к SQLite
     */
    private void setupSQLite(HikariConfig config, ConfigManager.DatabaseConfig dbConfig) {
        // Создание папки для базы данных если не существует
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        
        String dbPath = new File(dataFolder, dbConfig.sqliteFile).getAbsolutePath();
        
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setConnectionTestQuery("SELECT 1");
        
        // SQLite специфичные настройки
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("foreign_keys", "true");
        
        logger.info("Настройка SQLite: " + dbPath);
    }
    
    /**
     * Настройка подключения к MySQL
     */
    private void setupMySQL(HikariConfig config, ConfigManager.DatabaseConfig dbConfig) {
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8",
                dbConfig.mysqlHost, dbConfig.mysqlPort, dbConfig.mysqlDatabase));
        config.setUsername(dbConfig.mysqlUsername);
        config.setPassword(dbConfig.mysqlPassword);
        config.setConnectionTestQuery("SELECT 1");
        
        // MySQL специфичные настройки
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useLocalSessionState", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        
        logger.info("Настройка MySQL: " + dbConfig.mysqlHost + ":" + dbConfig.mysqlPort + "/" + dbConfig.mysqlDatabase);
    }
    
    /**
     * Проверка подключения к базе данных
     */
    private void testConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(5)) {
                logger.info("Подключение к базе данных успешно установлено");
            } else {
                throw new SQLException("Подключение к базе данных не валидно");
            }
        }
    }
    
    /**
     * Выполнение миграций базы данных
     */
    private void runMigrations() {
        try {
            logger.info("Выполнение миграций базы данных...");
            
            // Читаем SQL схему из ресурсов
            String schemaSQL = readSchemaFromResources();
            
            // Для SQLite не используем транзакции при создании таблиц
            try (Connection connection = getConnection();
                 Statement statement = connection.createStatement()) {
                
                // Разделяем SQL на отдельные команды, учитывая многострочные команды
                List<String> sqlCommands = parseSQLCommands(schemaSQL);
                
                logger.info("Найдено " + sqlCommands.size() + " SQL команд для выполнения");
                
                for (String sql : sqlCommands) {
                    sql = sql.trim();
                    if (!sql.isEmpty() && !sql.startsWith("--")) {
                        try {
                            statement.execute(sql);
                        } catch (SQLException e) {
                            // Игнорируем ошибки "уже существует" для SQLite
                            if (!e.getMessage().contains("already exists") && 
                                !e.getMessage().contains("duplicate column") &&
                                !e.getMessage().contains("object name already exists")) {
                                logger.warning("Ошибка при выполнении SQL: " + sql.substring(0, Math.min(sql.length(), 100)) + "... - " + e.getMessage());
                            }
                        }
                    }
                }
                
                logger.info("Миграции базы данных завершены");
            }
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Ошибка при выполнении миграций: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось выполнить миграции базы данных", e);
        }
    }
    
    /**
     * Чтение SQL схемы из ресурсов
     */
    private String readSchemaFromResources() throws IOException {
        StringBuilder content = new StringBuilder();
        
        try (Scanner scanner = new Scanner(
                plugin.getResource("database_schema.sql"), "UTF-8")) {
            
            while (scanner.hasNextLine()) {
                content.append(scanner.nextLine()).append("\n");
            }
        }
        
        return content.toString();
    }
    
    /**
     * Парсинг SQL команд с учетом многострочных команд
     */
    private List<String> parseSQLCommands(String sql) {
        List<String> commands = new ArrayList<>();
        StringBuilder currentCommand = new StringBuilder();
        boolean inTrigger = false;
        boolean inCreateTable = false;
        boolean inCreateIndex = false;
        int parenthesesLevel = 0;
        String currentConstruct = "";
        
        String[] lines = sql.split("\n");
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            
            // Пропускаем комментарии и пустые строки
            if (trimmedLine.isEmpty() || trimmedLine.startsWith("--")) {
                continue;
            }
            
            // Определяем начало различных конструкций
            String upperLine = trimmedLine.toUpperCase();
            
            // Если уже внутри конструкции
            if (inTrigger || inCreateTable || inCreateIndex) {
                currentCommand.append(" ").append(trimmedLine);
                
                // Подсчитываем скобки для CREATE TABLE
                if (inCreateTable) {
                    for (char c : trimmedLine.toCharArray()) {
                        if (c == '(') parenthesesLevel++;
                        else if (c == ')') parenthesesLevel--;
                    }
                    
                    // CREATE TABLE заканчивается когда скобки закрыты и есть ;
                    if (parenthesesLevel == 0 && trimmedLine.endsWith(";")) {
                        finishCommand(commands, currentCommand);
                        inCreateTable = false;
                        currentConstruct = "";
                    }
                } else if (inTrigger) {
                    // TRIGGER заканчивается на END;
                    if (upperLine.equals("END;") || upperLine.endsWith(" END;")) {
                        finishCommand(commands, currentCommand);
                        inTrigger = false;
                        currentConstruct = "";
                    }
                } else if (inCreateIndex) {
                    // INDEX - простая однострочная команда
                    if (trimmedLine.endsWith(";")) {
                        finishCommand(commands, currentCommand);
                        inCreateIndex = false;
                        currentConstruct = "";
                    }
                }
                continue;
            }
            
            // Завершаем предыдущую команду если начинается новая конструкция
            if (currentCommand.length() > 0 && 
                (upperLine.startsWith("CREATE TABLE") || 
                 upperLine.startsWith("CREATE TRIGGER") || 
                 upperLine.startsWith("CREATE INDEX"))) {
                finishCommand(commands, currentCommand);
            }
            
            // Обработка начала конструкций
            if (upperLine.startsWith("CREATE TRIGGER")) {
                inTrigger = true;
                currentConstruct = "TRIGGER";
                currentCommand.append(trimmedLine);
            } else if (upperLine.startsWith("CREATE TABLE")) {
                inCreateTable = true;
                currentConstruct = "TABLE";
                parenthesesLevel = 0;
                currentCommand.append(trimmedLine);
                
                // Подсчитываем скобки в первой строке
                for (char c : trimmedLine.toCharArray()) {
                    if (c == '(') parenthesesLevel++;
                    else if (c == ')') parenthesesLevel--;
                }
                
                // Если команда завершена в одной строке
                if (parenthesesLevel == 0 && trimmedLine.endsWith(";")) {
                    finishCommand(commands, currentCommand);
                    inCreateTable = false;
                    currentConstruct = "";
                }
            } else if (upperLine.startsWith("CREATE INDEX") || upperLine.startsWith("CREATE UNIQUE INDEX")) {
                inCreateIndex = true;
                currentConstruct = "INDEX";
                currentCommand.append(trimmedLine);
                
                // Если команда завершена в одной строке
                if (trimmedLine.endsWith(";")) {
                    finishCommand(commands, currentCommand);
                    inCreateIndex = false;
                    currentConstruct = "";
                }
            } else {
                // Обычная команда
                currentCommand.append(" ").append(trimmedLine);
                
                // Простые команды заканчиваются на ;
                if (trimmedLine.endsWith(";")) {
                    finishCommand(commands, currentCommand);
                }
            }
        }
        
        // Добавляем последнюю команду если есть
        if (currentCommand.length() > 0) {
            finishCommand(commands, currentCommand);
        }
        
        return commands;
    }
    
    /**
     * Завершить и добавить команду в список
     */
    private void finishCommand(List<String> commands, StringBuilder currentCommand) {
        String command = currentCommand.toString().trim();
        if (command.endsWith(";")) {
            command = command.substring(0, command.length() - 1).trim();
        }
        if (!command.isEmpty()) {
            commands.add(command);
        }
        currentCommand.setLength(0);
    }
    
    /**
     * Получить подключение к базе данных
     */
    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("База данных не инициализирована или закрыта");
        }
        return dataSource.getConnection();
    }
    
    /**
     * Получить DataSource для JOOQ
     */
    public DataSource getDataSource() {
        return dataSource;
    }
    
    /**
     * Проверка доступности базы данных
     */
    public boolean isAvailable() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        
        try (Connection connection = getConnection()) {
            return connection.isValid(2);
        } catch (SQLException e) {
            logger.warning("База данных недоступна: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Получить статистику пула соединений
     */
    public String getPoolStats() {
        if (dataSource == null) {
            return "DataSource не инициализирован";
        }
        
        return String.format("Pool Stats - Active: %d, Idle: %d, Total: %d, Waiting: %d",
                dataSource.getHikariPoolMXBean().getActiveConnections(),
                dataSource.getHikariPoolMXBean().getIdleConnections(),
                dataSource.getHikariPoolMXBean().getTotalConnections(),
                dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection());
    }
    
    /**
     * Создание резервной копии базы данных (только для SQLite)
     */
    public boolean createBackup() {
        ConfigManager.DatabaseConfig dbConfig = configManager.getDatabaseConfig();
        
        if (!"sqlite".equalsIgnoreCase(dbConfig.type)) {
            logger.info("Резервное копирование поддерживается только для SQLite");
            return false;
        }
        
        try {
            File dataFolder = plugin.getDataFolder();
            File dbFile = new File(dataFolder, dbConfig.sqliteFile);
            
            if (!dbFile.exists()) {
                logger.warning("Файл базы данных не найден: " + dbFile.getAbsolutePath());
                return false;
            }
            
            // Создание папки для резервных копий
            File backupFolder = new File(dataFolder, "backups");
            if (!backupFolder.exists()) {
                backupFolder.mkdirs();
            }
            
            // Имя файла резервной копии с временной меткой
            String timestamp = String.valueOf(System.currentTimeMillis());
            String backupFileName = "punishment_logs_backup_" + timestamp + ".db";
            File backupFile = new File(backupFolder, backupFileName);
            
            // Копирование файла
            Path source = dbFile.toPath();
            Path target = backupFile.toPath();
            Files.copy(source, target);
            
            logger.info("Резервная копия создана: " + backupFile.getAbsolutePath());
            
            // Удаление старых резервных копий (оставляем только последние 10)
            cleanupOldBackups(backupFolder);
            
            return true;
            
        } catch (IOException e) {
            logger.log(Level.WARNING, "Ошибка при создании резервной копии: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Очистка старых резервных копий
     */
    private void cleanupOldBackups(File backupFolder) {
        File[] backupFiles = backupFolder.listFiles((dir, name) -> 
                name.startsWith("punishment_logs_backup_") && name.endsWith(".db"));
        
        if (backupFiles == null || backupFiles.length <= 10) {
            return;
        }
        
        // Сортировка по времени создания (по имени файла с timestamp)
        java.util.Arrays.sort(backupFiles, (f1, f2) -> f1.getName().compareTo(f2.getName()));
        
        // Удаление старых файлов (оставляем последние 10)
        for (int i = 0; i < backupFiles.length - 10; i++) {
            if (backupFiles[i].delete()) {
                logger.fine("Удалена старая резервная копия: " + backupFiles[i].getName());
            }
        }
    }
    
    /**
     * Безопасное закрытие подключения к базе данных
     */
    public void shutdown() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                logger.info("Закрытие подключения к базе данных...");
                
                // Создание финальной резервной копии
                if (configManager.getPluginSettings().autoBackup) {
                    createBackup();
                }
                
                dataSource.close();
                logger.info("Подключение к базе данных закрыто");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ошибка при закрытии базы данных: " + e.getMessage(), e);
        }
    }
    
    // ==================== МЕТОДЫ ДЛЯ РАБОТЫ С НАКАЗАНИЯМИ ====================
    
    /**
     * Сохранить наказание в базу данных
     */
    public void savePunishment(PunishmentData punishment) {
        String sql = """
            INSERT INTO punishment_logs (
                type, player_uuid, player_name, moderator_uuid, moderator_name, 
                punishment_id, reason, duration, expires_at, jail_name,
                player_thread_id, moderator_thread_id, active, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, punishment.getType().name());
            stmt.setString(2, punishment.getPlayerUuid().toString());
            stmt.setString(3, punishment.getPlayerName());
            stmt.setString(4, punishment.getModeratorUuid() != null ? punishment.getModeratorUuid().toString() : null);
            stmt.setString(5, punishment.getModeratorName());
            stmt.setString(6, punishment.getPunishmentId());
            stmt.setString(7, punishment.getReason());
            stmt.setLong(8, punishment.getDuration() != null ? punishment.getDuration() : 0);
            stmt.setTimestamp(9, punishment.getExpiresAt() != null ? Timestamp.from(punishment.getExpiresAt()) : null);
            stmt.setString(10, punishment.getJailName());
            stmt.setLong(11, punishment.getPlayerThreadId() != null ? punishment.getPlayerThreadId() : 0);
            stmt.setLong(12, punishment.getModeratorThreadId() != null ? punishment.getModeratorThreadId() : 0);
            stmt.setBoolean(13, punishment.isActive());
            stmt.setTimestamp(14, Timestamp.from(punishment.getCreatedAt()));
            
            stmt.executeUpdate();
            
            try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    punishment.setId(generatedKeys.getLong(1));
                }
            }
            
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Ошибка при сохранении наказания: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось сохранить наказание в базу данных", e);
        }
    }
    
    /**
     * Обновить наказание в базе данных
     */
    public void updatePunishment(PunishmentData punishment) {
        String sql = """
            UPDATE punishment_logs SET 
                player_thread_id = ?, moderator_thread_id = ?, 
                player_message_id = ?, moderator_message_id = ?, log_message_id = ?,
                active = ?, unbanned_at = ?, unban_reason = ?, 
                unban_moderator_uuid = ?, unban_moderator_name = ?, unban_type = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, punishment.getPlayerThreadId() != null ? punishment.getPlayerThreadId() : 0);
            stmt.setLong(2, punishment.getModeratorThreadId() != null ? punishment.getModeratorThreadId() : 0);
            stmt.setLong(3, punishment.getPlayerMessageId() != null ? punishment.getPlayerMessageId() : 0);
            stmt.setLong(4, punishment.getModeratorMessageId() != null ? punishment.getModeratorMessageId() : 0);
            stmt.setLong(5, punishment.getLogMessageId() != null ? punishment.getLogMessageId() : 0);
            stmt.setBoolean(6, punishment.isActive());
            stmt.setTimestamp(7, punishment.getUnbannedAt() != null ? Timestamp.from(punishment.getUnbannedAt()) : null);
            stmt.setString(8, punishment.getUnbanReason());
            stmt.setString(9, punishment.getUnbanModeratorUuid() != null ? punishment.getUnbanModeratorUuid().toString() : null);
            stmt.setString(10, punishment.getUnbanModeratorName());
            stmt.setString(11, punishment.getUnbanType() != null ? punishment.getUnbanType().name() : null);
            stmt.setLong(12, punishment.getId());
            
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Ошибка при обновлении наказания: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось обновить наказание в базе данных", e);
        }
    }
    
    /**
     * Получить наказание по ID наказания (из LiteBans/CMI)
     */
    public PunishmentData getPunishmentByPunishmentId(String punishmentId) {
        String sql = "SELECT * FROM punishment_logs WHERE punishment_id = ? AND active = true";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, punishmentId);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToPunishment(rs);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при поиске наказания по ID: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Получить истекшие наказания
     */
    public List<PunishmentData> getExpiredPunishments() {
        String sql = """
            SELECT * FROM punishment_logs 
            WHERE active = true AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
            """;
        
        List<PunishmentData> expired = new ArrayList<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                expired.add(mapResultSetToPunishment(rs));
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при поиске истекших наказаний: " + e.getMessage(), e);
        }
        
        return expired;
    }
    
    // ==================== МЕТОДЫ ДЛЯ РАБОТЫ С ИГРОКАМИ ====================
    
    /**
     * Получить игрока по UUID
     */
    public PlayerData getPlayerByUuid(UUID playerUuid) {
        String sql = "SELECT * FROM players WHERE player_uuid = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToPlayer(rs);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при поиске игрока: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Сохранить или обновить игрока
     */
    public void saveOrUpdatePlayer(PlayerData player) {
        String sql = """
            INSERT OR REPLACE INTO players (
                player_uuid, player_name, discord_thread_id, 
                total_punishments, active_punishments, last_punishment_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, player.getPlayerUuid().toString());
            stmt.setString(2, player.getPlayerName());
            stmt.setLong(3, player.getDiscordThreadId() != null ? player.getDiscordThreadId() : 0);
            stmt.setInt(4, player.getTotalPunishments());
            stmt.setInt(5, player.getActivePunishments());
            stmt.setTimestamp(6, player.getLastPunishmentAt() != null ? Timestamp.from(player.getLastPunishmentAt()) : null);
            
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Ошибка при сохранении игрока: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось сохранить игрока в базу данных", e);
        }
    }
    
    // ==================== МЕТОДЫ ДЛЯ РАБОТЫ С МОДЕРАТОРАМИ ====================
    
    /**
     * Получить модератора по UUID
     */
    public ModeratorData getModeratorByUuid(UUID moderatorUuid) {
        String sql = "SELECT * FROM moderators WHERE moderator_uuid = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, moderatorUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToModerator(rs);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при поиске модератора: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Получить модератора по ID ветки Discord
     */
    public ModeratorData getModeratorByThreadId(long threadId) {
        String sql = "SELECT * FROM moderators WHERE discord_thread_id = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, threadId);
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return mapResultSetToModerator(rs);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при поиске модератора по ветке: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Сохранить или обновить модератора
     */
    public void saveOrUpdateModerator(ModeratorData moderator) {
        String sql = """
            INSERT OR REPLACE INTO moderators (
                moderator_uuid, moderator_name, discord_id, discord_thread_id,
                total_issued, active_issued, last_action_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """;
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, moderator.getModeratorUuid().toString());
            stmt.setString(2, moderator.getModeratorName());
            stmt.setLong(3, moderator.getDiscordId() != null ? moderator.getDiscordId() : 0);
            stmt.setLong(4, moderator.getDiscordThreadId() != null ? moderator.getDiscordThreadId() : 0);
            stmt.setInt(5, moderator.getTotalIssued());
            stmt.setInt(6, moderator.getActiveIssued());
            stmt.setTimestamp(7, moderator.getLastActionAt() != null ? Timestamp.from(moderator.getLastActionAt()) : null);
            
            stmt.executeUpdate();
            
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Ошибка при сохранении модератора: " + e.getMessage(), e);
            throw new RuntimeException("Не удалось сохранить модератора в базу данных", e);
        }
    }
    
    // ==================== МЕТОДЫ СТАТИСТИКИ ====================
    
    /**
     * Получить статистику наказаний игрока
     */
    public Map<PunishmentType, Integer> getPlayerPunishmentCounts(UUID playerUuid) {
        String sql = "SELECT type, COUNT(*) as count FROM punishment_logs WHERE player_uuid = ? GROUP BY type";
        Map<PunishmentType, Integer> counts = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                PunishmentType type = PunishmentType.valueOf(rs.getString("type"));
                int count = rs.getInt("count");
                counts.put(type, count);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при получении статистики игрока: " + e.getMessage(), e);
        }
        
        return counts;
    }
    
    /**
     * Получить количество активных наказаний игрока по типам
     */
    public Map<PunishmentType, Integer> getPlayerActivePunishmentCounts(UUID playerUuid) {
        String sql = "SELECT type, COUNT(*) as count FROM punishment_logs WHERE player_uuid = ? AND active = true GROUP BY type";
        Map<PunishmentType, Integer> counts = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                PunishmentType type = PunishmentType.valueOf(rs.getString("type"));
                int count = rs.getInt("count");
                counts.put(type, count);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при подсчете активных наказаний игрока по типам: " + e.getMessage(), e);
        }
        
        return counts;
    }
    
    /**
     * Получить список активных наказаний игрока
     */
    public List<PunishmentData> getPlayerActivePunishments(UUID playerUuid) {
        String sql = "SELECT * FROM punishment_logs WHERE player_uuid = ? AND active = true ORDER BY created_at DESC";
        List<PunishmentData> punishments = new ArrayList<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                punishments.add(mapResultSetToPunishment(rs));
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при получении активных наказаний игрока: " + e.getMessage(), e);
        }
        
        return punishments;
    }
    
    /**
     * Получить статистику выданных наказаний модератора
     */
    public Map<PunishmentType, Integer> getModeratorIssuedCounts(UUID moderatorUuid) {
        String sql = "SELECT type, COUNT(*) as count FROM punishment_logs WHERE moderator_uuid = ? GROUP BY type";
        Map<PunishmentType, Integer> counts = new HashMap<>();
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, moderatorUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                PunishmentType type = PunishmentType.valueOf(rs.getString("type"));
                int count = rs.getInt("count");
                counts.put(type, count);
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при получении статистики модератора: " + e.getMessage(), e);
        }
        
        return counts;
    }
    
    /**
     * Получить общее количество наказаний игрока
     */
    public int getPlayerTotalPunishments(UUID playerUuid) {
        String sql = "SELECT COUNT(*) as count FROM punishment_logs WHERE player_uuid = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt("count");
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при подсчете наказаний игрока: " + e.getMessage(), e);
        }
        
        return 0;
    }
    
    /**
     * Получить количество активных наказаний игрока
     */
    public int getPlayerActivePunishmentsCount(UUID playerUuid) {
        String sql = "SELECT COUNT(*) as count FROM punishment_logs WHERE player_uuid = ? AND active = true";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt("count");
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при подсчете активных наказаний игрока: " + e.getMessage(), e);
        }
        
        return 0;
    }
    
    /**
     * Получить общее количество выданных наказаний модератора
     */
    public int getModeratorTotalIssued(UUID moderatorUuid) {
        String sql = "SELECT COUNT(*) as count FROM punishment_logs WHERE moderator_uuid = ?";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, moderatorUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt("count");
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при подсчете наказаний модератора: " + e.getMessage(), e);
        }
        
        return 0;
    }
    
    /**
     * Получить количество активных наказаний выданных модератором
     */
    public int getModeratorActiveIssued(UUID moderatorUuid) {
        String sql = "SELECT COUNT(*) as count FROM punishment_logs WHERE moderator_uuid = ? AND active = true";
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, moderatorUuid.toString());
            ResultSet rs = stmt.executeQuery();
            
            if (rs.next()) {
                return rs.getInt("count");
            }
            
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Ошибка при подсчете активных наказаний модератора: " + e.getMessage(), e);
        }
        
        return 0;
    }
    
    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================
    
    /**
     * Преобразование ResultSet в PunishmentData
     */
    private PunishmentData mapResultSetToPunishment(ResultSet rs) throws SQLException {
        PunishmentData punishment = new PunishmentData();
        
        punishment.setId(rs.getLong("id"));
        punishment.setType(PunishmentType.valueOf(rs.getString("type")));
        punishment.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        punishment.setPlayerName(rs.getString("player_name"));
        
        String moderatorUuidStr = rs.getString("moderator_uuid");
        if (moderatorUuidStr != null) {
            punishment.setModeratorUuid(UUID.fromString(moderatorUuidStr));
        }
        
        punishment.setModeratorName(rs.getString("moderator_name"));
        punishment.setPunishmentId(rs.getString("punishment_id"));
        punishment.setReason(rs.getString("reason"));
        
        long duration = rs.getLong("duration");
        if (duration > 0) {
            punishment.setDuration(duration);
        }
        
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        if (expiresAt != null) {
            punishment.setExpiresAt(expiresAt.toInstant());
        }
        
        punishment.setJailName(rs.getString("jail_name"));
        
        long playerThreadId = rs.getLong("player_thread_id");
        if (playerThreadId > 0) {
            punishment.setPlayerThreadId(playerThreadId);
        }
        
        long moderatorThreadId = rs.getLong("moderator_thread_id");
        if (moderatorThreadId > 0) {
            punishment.setModeratorThreadId(moderatorThreadId);
        }
        
        long playerMessageId = rs.getLong("player_message_id");
        if (playerMessageId > 0) {
            punishment.setPlayerMessageId(playerMessageId);
        }
        
        long moderatorMessageId = rs.getLong("moderator_message_id");
        if (moderatorMessageId > 0) {
            punishment.setModeratorMessageId(moderatorMessageId);
        }
        
        long logMessageId = rs.getLong("log_message_id");
        if (logMessageId > 0) {
            punishment.setLogMessageId(logMessageId);
        }
        
        punishment.setActive(rs.getBoolean("active"));
        
        Timestamp unbannedAt = rs.getTimestamp("unbanned_at");
        if (unbannedAt != null) {
            punishment.setUnbannedAt(unbannedAt.toInstant());
        }
        
        punishment.setUnbanReason(rs.getString("unban_reason"));
        
        String unbanModeratorUuidStr = rs.getString("unban_moderator_uuid");
        if (unbanModeratorUuidStr != null) {
            punishment.setUnbanModeratorUuid(UUID.fromString(unbanModeratorUuidStr));
        }
        
        punishment.setUnbanModeratorName(rs.getString("unban_moderator_name"));
        
        String unbanTypeStr = rs.getString("unban_type");
        if (unbanTypeStr != null) {
            punishment.setUnbanType(UnbanType.valueOf(unbanTypeStr));
        }
        
        punishment.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        punishment.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        
        return punishment;
    }
    
    /**
     * Преобразование ResultSet в PlayerData
     */
    private PlayerData mapResultSetToPlayer(ResultSet rs) throws SQLException {
        PlayerData player = new PlayerData();
        
        player.setId(rs.getLong("id"));
        player.setPlayerUuid(UUID.fromString(rs.getString("player_uuid")));
        player.setPlayerName(rs.getString("player_name"));
        
        long threadId = rs.getLong("discord_thread_id");
        if (threadId > 0) {
            player.setDiscordThreadId(threadId);
        }
        
        player.setTotalPunishments(rs.getInt("total_punishments"));
        player.setActivePunishments(rs.getInt("active_punishments"));
        
        Timestamp lastPunishment = rs.getTimestamp("last_punishment_at");
        if (lastPunishment != null) {
            player.setLastPunishmentAt(lastPunishment.toInstant());
        }
        
        player.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        player.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        
        return player;
    }
    
    /**
     * Преобразование ResultSet в ModeratorData
     */
    private ModeratorData mapResultSetToModerator(ResultSet rs) throws SQLException {
        ModeratorData moderator = new ModeratorData();
        
        moderator.setId(rs.getLong("id"));
        moderator.setModeratorUuid(UUID.fromString(rs.getString("moderator_uuid")));
        moderator.setModeratorName(rs.getString("moderator_name"));
        
        long discordId = rs.getLong("discord_id");
        if (discordId > 0) {
            moderator.setDiscordId(discordId);
        }
        
        long threadId = rs.getLong("discord_thread_id");
        if (threadId > 0) {
            moderator.setDiscordThreadId(threadId);
        }
        
        moderator.setTotalIssued(rs.getInt("total_issued"));
        moderator.setActiveIssued(rs.getInt("active_issued"));
        
        Timestamp lastAction = rs.getTimestamp("last_action_at");
        if (lastAction != null) {
            moderator.setLastActionAt(lastAction.toInstant());
        }
        
        moderator.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        moderator.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        
        return moderator;
    }
} 