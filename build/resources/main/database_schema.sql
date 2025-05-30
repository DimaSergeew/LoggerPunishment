-- SQL схема для плагина LoggerPunishment
-- Используется для генерации JOOQ классов

-- Таблица конфигурации Discord каналов
CREATE TABLE discord_config (
    id INTEGER PRIMARY KEY,
    players_forum_id BIGINT NOT NULL,
    moderators_forum_id BIGINT NOT NULL,
    log_channel_id BIGINT NOT NULL,
    no_link_channel_id BIGINT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Таблица модераторов
CREATE TABLE IF NOT EXISTS moderators (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    moderator_uuid VARCHAR(36) NOT NULL UNIQUE,
    moderator_name VARCHAR(16) NOT NULL,
    discord_id BIGINT NULL,
    discord_thread_id BIGINT NULL,
    total_issued INTEGER DEFAULT 0,
    active_issued INTEGER DEFAULT 0,
    last_action_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Таблица игроков
CREATE TABLE IF NOT EXISTS players (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid VARCHAR(36) NOT NULL UNIQUE,
    player_name VARCHAR(16) NOT NULL,
    discord_thread_id BIGINT NULL,
    total_punishments INTEGER DEFAULT 0,
    active_punishments INTEGER DEFAULT 0,
    last_punishment_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Основная таблица наказаний
CREATE TABLE IF NOT EXISTS punishment_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    
    -- Основная информация
    type VARCHAR(10) NOT NULL, -- BAN, MUTE, KICK, JAIL
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(16) NOT NULL,
    moderator_uuid VARCHAR(36) NULL,
    moderator_name VARCHAR(32) NOT NULL,
    punishment_id VARCHAR(50) NULL, -- ID из LiteBans/CMI
    
    -- Детали наказания
    reason TEXT NOT NULL,
    duration BIGINT NULL, -- В секундах, NULL = permanent
    expires_at TIMESTAMP NULL,
    jail_name VARCHAR(32) NULL, -- Для jail наказаний
    
    -- Discord сообщения
    player_thread_id BIGINT NULL,
    moderator_thread_id BIGINT NULL,
    player_message_id BIGINT NULL,
    moderator_message_id BIGINT NULL,
    log_message_id BIGINT NULL,
    
    -- Статус наказания
    active BOOLEAN DEFAULT TRUE,
    
    -- Информация о снятии наказания
    unbanned_at TIMESTAMP NULL,
    unban_reason TEXT NULL,
    unban_moderator_uuid VARCHAR(36) NULL,
    unban_moderator_name VARCHAR(32) NULL,
    unban_type VARCHAR(10) NULL, -- MANUAL, EXPIRED, APPEAL
    
    -- Системные поля
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Индексы для быстрого поиска
    CONSTRAINT fk_player FOREIGN KEY (player_uuid) REFERENCES players (player_uuid),
    CONSTRAINT fk_moderator FOREIGN KEY (moderator_uuid) REFERENCES moderators (moderator_uuid)
);

-- Индексы для оптимизации запросов
CREATE INDEX IF NOT EXISTS idx_player_uuid ON punishment_logs (player_uuid);
CREATE INDEX IF NOT EXISTS idx_moderator_uuid ON punishment_logs (moderator_uuid);
CREATE INDEX IF NOT EXISTS idx_punishment_id ON punishment_logs (punishment_id);
CREATE INDEX IF NOT EXISTS idx_active ON punishment_logs (active);
CREATE INDEX IF NOT EXISTS idx_expires_at ON punishment_logs (expires_at);
CREATE INDEX IF NOT EXISTS idx_created_at ON punishment_logs (created_at);

-- Индексы для таблицы игроков
CREATE INDEX IF NOT EXISTS idx_players_uuid ON players (player_uuid);
CREATE INDEX IF NOT EXISTS idx_players_name ON players (player_name);
CREATE INDEX IF NOT EXISTS idx_players_thread ON players (discord_thread_id);

-- Индексы для таблицы модераторов  
CREATE INDEX IF NOT EXISTS idx_moderators_uuid ON moderators (moderator_uuid);
CREATE INDEX IF NOT EXISTS idx_moderators_discord ON moderators (discord_id);
CREATE INDEX IF NOT EXISTS idx_moderators_thread ON moderators (discord_thread_id);

-- Триггеры для автоматического обновления статистики
CREATE TRIGGER IF NOT EXISTS update_player_stats_insert
AFTER INSERT ON punishment_logs
FOR EACH ROW
BEGIN
    UPDATE players 
    SET total_punishments = total_punishments + 1,
        active_punishments = active_punishments + CASE WHEN NEW.active = 1 THEN 1 ELSE 0 END,
        last_punishment_at = NEW.created_at,
        updated_at = CURRENT_TIMESTAMP
    WHERE player_uuid = NEW.player_uuid;
    
    -- Вставить игрока если не существует
    INSERT OR IGNORE INTO players (player_uuid, player_name, total_punishments, active_punishments, last_punishment_at)
    VALUES (NEW.player_uuid, NEW.player_name, 1, CASE WHEN NEW.active = 1 THEN 1 ELSE 0 END, NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS update_moderator_stats_insert
AFTER INSERT ON punishment_logs
FOR EACH ROW
WHEN NEW.moderator_uuid IS NOT NULL
BEGIN
    UPDATE moderators 
    SET total_issued = total_issued + 1,
        active_issued = active_issued + CASE WHEN NEW.active = 1 THEN 1 ELSE 0 END,
        last_action_at = NEW.created_at,
        updated_at = CURRENT_TIMESTAMP
    WHERE moderator_uuid = NEW.moderator_uuid;
    
    -- Вставить модератора если не существует
    INSERT OR IGNORE INTO moderators (moderator_uuid, moderator_name, total_issued, active_issued, last_action_at)
    VALUES (NEW.moderator_uuid, NEW.moderator_name, 1, CASE WHEN NEW.active = 1 THEN 1 ELSE 0 END, NEW.created_at);
END;

CREATE TRIGGER IF NOT EXISTS update_stats_on_unban
AFTER UPDATE OF active ON punishment_logs
FOR EACH ROW
WHEN OLD.active = 1 AND NEW.active = 0
BEGIN
    UPDATE players 
    SET active_punishments = active_punishments - 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE player_uuid = NEW.player_uuid;
    
    UPDATE moderators 
    SET active_issued = active_issued - 1,
        updated_at = CURRENT_TIMESTAMP
    WHERE moderator_uuid = NEW.moderator_uuid AND NEW.moderator_uuid IS NOT NULL;
END;

-- Таблица статистики (денормализованная таблица для быстрых запросов)
CREATE TABLE punishment_statistics (
    id BIGINT PRIMARY KEY AUTOINCREMENT,
    target_uuid VARCHAR(36) NOT NULL,
    target_type VARCHAR(10) NOT NULL CHECK (target_type IN ('PLAYER', 'MODERATOR')),
    
    -- Счетчики по типам наказаний
    total_punishments INTEGER DEFAULT 0,
    total_bans INTEGER DEFAULT 0,
    total_mutes INTEGER DEFAULT 0,
    total_kicks INTEGER DEFAULT 0,
    total_jails INTEGER DEFAULT 0,
    
    -- Счетчики активных наказаний
    active_punishments INTEGER DEFAULT 0,
    active_bans INTEGER DEFAULT 0,
    active_mutes INTEGER DEFAULT 0,
    active_jails INTEGER DEFAULT 0,
    
    -- Временные метки
    first_punishment_at TIMESTAMP,
    last_punishment_at TIMESTAMP,
    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    UNIQUE(target_uuid, target_type)
);

-- Индексы для статистики
CREATE INDEX idx_statistics_target ON punishment_statistics(target_uuid, target_type);
CREATE INDEX idx_statistics_updated ON punishment_statistics(last_updated_at);

-- Таблица очереди Discord действий (для офлайн режима)
CREATE TABLE discord_queue (
    id BIGINT PRIMARY KEY AUTOINCREMENT,
    action_type VARCHAR(20) NOT NULL CHECK (action_type IN ('SEND_MESSAGE', 'EDIT_MESSAGE', 'DELETE_MESSAGE', 'CREATE_THREAD', 'UPDATE_STATS')),
    target_channel_id BIGINT NOT NULL,
    target_message_id BIGINT,
    target_thread_id BIGINT,
    
    -- Данные действия (JSON)
    action_data TEXT NOT NULL,
    
    -- Метаданные
    priority INTEGER DEFAULT 0,
    max_attempts INTEGER DEFAULT 3,
    current_attempts INTEGER DEFAULT 0,
    last_attempt_at TIMESTAMP,
    next_attempt_at TIMESTAMP,
    
    -- Статус
    status VARCHAR(10) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    error_message TEXT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Индексы для очереди
CREATE INDEX idx_queue_status ON discord_queue(status);
CREATE INDEX idx_queue_next_attempt ON discord_queue(next_attempt_at);
CREATE INDEX idx_queue_priority ON discord_queue(priority DESC);
CREATE INDEX idx_queue_created ON discord_queue(created_at);

-- Таблица логов плагина
CREATE TABLE plugin_logs (
    id BIGINT PRIMARY KEY AUTOINCREMENT,
    level VARCHAR(10) NOT NULL CHECK (level IN ('DEBUG', 'INFO', 'WARN', 'ERROR')),
    category VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,
    details TEXT,
    
    -- Контекст
    player_uuid VARCHAR(36),
    moderator_uuid VARCHAR(36),
    punishment_id VARCHAR(100),
    discord_message_id BIGINT,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Индексы для логов
CREATE INDEX idx_logs_level ON plugin_logs(level);
CREATE INDEX idx_logs_category ON plugin_logs(category);
CREATE INDEX idx_logs_created ON plugin_logs(created_at);
CREATE INDEX idx_logs_player ON plugin_logs(player_uuid);

-- Таблица настроек плагина (runtime конфигурация)
CREATE TABLE plugin_settings (
    key VARCHAR(50) PRIMARY KEY,
    value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Вставка начальных данных
INSERT INTO discord_config (id, players_forum_id, moderators_forum_id, log_channel_id) 
VALUES (1, 0, 0, 0);

-- Вставка настроек по умолчанию
INSERT INTO plugin_settings (key, value, description) VALUES 
('last_cleanup_time', '0', 'Время последней очистки старых записей'),
('stats_last_update', '0', 'Время последнего обновления статистики'),
('discord_last_reconnect', '0', 'Время последней попытки переподключения к Discord'),
('plugin_version', '1.0', 'Версия плагина для миграций');

-- Триггеры для автоматического обновления updated_at
CREATE TRIGGER update_moderators_updated_at 
AFTER UPDATE ON moderators 
BEGIN 
    UPDATE moderators SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id; 
END;

CREATE TRIGGER update_players_updated_at 
AFTER UPDATE ON players 
BEGIN 
    UPDATE players SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id; 
END;

CREATE TRIGGER update_punishments_updated_at 
AFTER UPDATE ON punishment_logs 
BEGIN 
    UPDATE punishment_logs SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id; 
END;

CREATE TRIGGER update_statistics_updated_at 
AFTER UPDATE ON punishment_statistics 
BEGIN 
    UPDATE punishment_statistics SET last_updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id; 
END;

CREATE TRIGGER update_queue_updated_at 
AFTER UPDATE ON discord_queue 
BEGIN 
    UPDATE discord_queue SET updated_at = CURRENT_TIMESTAMP WHERE id = NEW.id; 
END; 